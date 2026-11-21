"""码忆 CodeMem 评测执行器。

用法：
    python run_eval.py --base http://127.0.0.1:8080 --learner eval-e --project java --out result.json

指标：
    recall@5        期望笔记是否出现在前 5 条引用（note_fact）
    keyword_hit     期望关键词是否出现在引用片段里（note_fact）
    mode_accuracy   路由模式是否与标注一致（全部）
    reviewer_coverage 期望双角色的题目是否真的出现 reviewer 步骤
    memory_recall   跨会话题是否带上工作记忆（memoryCount > 0）
    state_accuracy  抽取后状态字段是否符合标注（study_plan / contradiction）
    first_token_p95 首 token 延迟（全部）
    avg_tokens      单轮输入 + 输出 token（来自 agent_run 记录，缺失时按字符估算）
"""

import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_BASE = 'http://127.0.0.1:8080'
GOLDEN_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'golden.jsonl')

JSON_HEADERS = {'Content-Type': 'application/json; charset=utf-8'}


def post_json(base, path, payload, timeout=120):
    data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    request = urllib.request.Request(base + path, data=data, headers=JSON_HEADERS)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def get_json(base, path, timeout=30):
    with urllib.request.urlopen(base + path, timeout=timeout) as response:
        return json.load(response)


def stream_chat(base, learner, project, conversation_id, message, timeout=120):
    payload = {
        'learnerId': learner,
        'projectId': project,
        'message': message,
    }
    if conversation_id is not None:
        payload['conversationId'] = conversation_id
    data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    request = urllib.request.Request(
        base + '/api/v1/chat/stream',
        data=data,
        headers={**JSON_HEADERS, 'Accept': 'text/event-stream'}
    )

    started = time.perf_counter()
    first_token_ms = None
    answer_parts = []
    citations = []
    agents = []
    meta = None
    done = None
    error = None

    with urllib.request.urlopen(request, timeout=timeout) as response:
        event_name = None
        data_lines = []
        for raw in response:
            line = raw.decode('utf-8').rstrip('\r\n')
            if line == '':
                if event_name:
                    parsed = None
                    if data_lines:
                        text = '\n'.join(data_lines)
                        try:
                            parsed = json.loads(text)
                        except json.JSONDecodeError:
                            parsed = text
                    if event_name == 'meta':
                        meta = parsed
                    elif event_name == 'citation':
                        citations.append(parsed)
                    elif event_name == 'agent':
                        agents.append(parsed)
                    elif event_name == 'token':
                        if first_token_ms is None:
                            first_token_ms = (time.perf_counter() - started) * 1000
                        answer_parts.append((parsed or {}).get('delta', ''))
                    elif event_name == 'done':
                        done = parsed
                    elif event_name == 'error':
                        error = parsed
                event_name = None
                data_lines = []
                continue
            if line.startswith('event:'):
                event_name = line[6:].strip()
            elif line.startswith('data:'):
                data_lines.append(line[5:].strip())

    elapsed_ms = (time.perf_counter() - started) * 1000
    return {
        'answer': ''.join(answer_parts),
        'citations': citations,
        'agents': agents,
        'meta': meta,
        'done': done,
        'error': error,
        'first_token_ms': first_token_ms,
        'elapsed_ms': elapsed_ms,
    }


def wait_outbox_idle(base, max_wait=20.0):
    deadline = time.time() + max_wait
    while time.time() < deadline:
        try:
            status = get_json(base, '/api/v1/outbox/status')
        except urllib.error.URLError:
            return False
        if status.get('pending', 0) == 0 and status.get('processing', 0) == 0:
            return True
        time.sleep(0.5)
    return False


def state_satisfied(state, path, contains):
    if not path:
        return None
    value = (state or {}).get(path)
    if path == 'currentBlocker':
        topic = (value or {}).get('topic', '')
        return bool(topic) and (contains is None or contains in topic)
    if isinstance(value, list):
        return len(value) > 0
    return value not in (None, '', [], {})


def load_golden(path=GOLDEN_PATH):
    items = []
    with open(path, encoding='utf-8') as handle:
        for line in handle:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


def run_item(base, learner, project, conversation_id, item):
    if item.get('seed'):
        stream_chat(base, learner, project, conversation_id, item['seed'])
        wait_outbox_idle(base, 10)

    result = stream_chat(base, learner, project, conversation_id, item['query'])
    conversation_id = (result.get('meta') or {}).get('conversationId', conversation_id)
    trace_id = (result.get('meta') or {}).get('traceId')

    state = None
    if item.get('expect_state_path'):
        wait_outbox_idle(base)
        state = get_json(
            base,
            '/api/v1/state?' + urllib.parse.urlencode({'learnerId': learner, 'projectId': project})
        )

    run_detail = None
    if trace_id:
        try:
            run_detail = get_json(base, '/api/v1/agent/runs/' + trace_id)
        except urllib.error.HTTPError:
            run_detail = None

    citation_paths = [citation.get('path') for citation in result['citations']]
    expected_paths = item.get('expect_citations') or []
    recall_hit = None
    if expected_paths:
        recall_hit = any(path in citation_paths[:5] for path in expected_paths)

    keywords = item.get('expect_keywords') or []
    keyword_hit = None
    if keywords:
        joined = ' '.join(str(citation.get('snippet', '')) for citation in result['citations'])
        keyword_hit = any(keyword in joined for keyword in keywords)

    workflow = (run_detail or {}).get('run', {}).get('workflow')
    steps = (run_detail or {}).get('steps', []) or []
    reviewer_steps = [step for step in steps if step.get('agentName') == 'reviewer']

    tokens = None
    if run_detail and run_detail.get('run'):
        run = run_detail['run']
        prompt_tokens = run.get('promptTokens') or 0
        completion_tokens = run.get('completionTokens') or 0
        if prompt_tokens or completion_tokens:
            tokens = prompt_tokens + completion_tokens
    if tokens is None:
        tokens = int((len(item['query']) + len(result['answer'])) / 1.5)

    done = result.get('done') or {}
    return {
        'id': item['id'],
        'type': item['type'],
        'mode_expected': item.get('expect_mode'),
        'workflow': workflow,
        'mode_ok': None if not item.get('expect_mode') else workflow == item['expect_mode'],
        'steps': [step.get('agentName') for step in steps],
        'reviewer': bool(reviewer_steps),
        'recall_hit': recall_hit,
        'keyword_hit': keyword_hit,
        'citations': len(result['citations']),
        'memory_count': done.get('memoryCount'),
        'state_ok': state_satisfied(state, item.get('expect_state_path'), item.get('expect_state_contains')),
        'first_token_ms': result['first_token_ms'],
        'elapsed_ms': result['elapsed_ms'],
        'tokens': tokens,
        'error': result.get('error'),
        'conversation_id': conversation_id,
        'trace_id': trace_id,
    }


def percentile(values, ratio):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(ratio * (len(ordered) - 1)))))
    return ordered[index]


def ratio(flags):
    flags = [flag for flag in flags if flag is not None]
    if not flags:
        return None
    return round(sum(1 for flag in flags if flag) / len(flags), 3)


def aggregate(rows):
    first_tokens = [row['first_token_ms'] for row in rows if row['first_token_ms'] is not None]
    metrics = {
        'items': len(rows),
        'errors': sum(1 for row in rows if row['error']),
        'recall_at_5': ratio([row['recall_hit'] for row in rows if row['type'] == 'note_fact']),
        'keyword_hit': ratio([row['keyword_hit'] for row in rows if row['type'] == 'note_fact']),
        'mode_accuracy': ratio([row['mode_ok'] for row in rows]),
        'reviewer_coverage': ratio([row['reviewer'] for row in rows if row['mode_expected'] == 'DUAL']),
        'memory_recall': ratio([
            1 if (row['memory_count'] or 0) > 0 else 0
            for row in rows if row['type'] == 'cross_session'
        ]),
        'state_accuracy': ratio([row['state_ok'] for row in rows if row['state_ok'] is not None]),
        'first_token_p50_ms': round(percentile(first_tokens, 0.5), 1) if first_tokens else None,
        'first_token_p95_ms': round(percentile(first_tokens, 0.95), 1) if first_tokens else None,
        'avg_tokens': round(statistics.fmean([row['tokens'] for row in rows if row['tokens']]), 1),
        'samples': {
            'note_fact': sum(1 for row in rows if row['type'] == 'note_fact'),
            'cross_session': sum(1 for row in rows if row['type'] == 'cross_session'),
            'study_plan': sum(1 for row in rows if row['type'] == 'study_plan'),
            'code_review': sum(1 for row in rows if row['type'] == 'code_review'),
            'contradiction': sum(1 for row in rows if row['type'] == 'contradiction'),
        },
    }
    by_type = {}
    for item_type in metrics['samples']:
        subset = [row for row in rows if row['type'] == item_type]
        by_type[item_type] = {
            'items': len(subset),
            'mode_accuracy': ratio([row['mode_ok'] for row in subset]),
            'recall_at_5': ratio([row['recall_hit'] for row in subset]),
            'state_accuracy': ratio([row['state_ok'] for row in subset if row['state_ok'] is not None]),
            'first_token_p95_ms': (
                round(percentile([row['first_token_ms'] for row in subset if row['first_token_ms']], 0.95), 1)
                if any(row['first_token_ms'] for row in subset) else None
            ),
        }
    return metrics, by_type


def evaluate(base=DEFAULT_BASE, learner='eval', project='java', limit=None, verbose=True):
    items = load_golden()
    if limit:
        items = items[:limit]
    rows = []
    conversation_id = None
    for index, item in enumerate(items, start=1):
        row = run_item(base, learner, project, conversation_id, item)
        conversation_id = row['conversation_id']
        rows.append(row)
        if verbose:
            print(
                f"[{index}/{len(items)}] {row['id']} {row['type']} workflow={row['workflow']} "
                f"recall={row['recall_hit']} state={row['state_ok']} tokens={row['tokens']}"
            )
    metrics, by_type = aggregate(rows)
    return {'metrics': metrics, 'by_type': by_type, 'items': rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default=DEFAULT_BASE)
    parser.add_argument('--learner', default='eval')
    parser.add_argument('--project', default='java-backend')
    parser.add_argument('--limit', type=int, default=None)
    parser.add_argument('--out', default=None)
    args = parser.parse_args()

    result = evaluate(args.base, args.learner, args.project, args.limit)
    print(json.dumps(result['metrics'], ensure_ascii=False, indent=2))
    if args.out:
        with open(args.out, 'w', encoding='utf-8') as handle:
            json.dump(result, handle, ensure_ascii=False, indent=2)
        print(f'written {args.out}')
    return 0


if __name__ == '__main__':
    sys.exit(main())