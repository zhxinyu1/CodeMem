"""码忆 CodeMem 消融实验：逐组启动后端并跑同一份黄金集。

用法：
    python ablation.py --jar ../../target/codemem-0.1.0-SNAPSHOT.jar --out report.md

每组用独立 learnerId（eval-a … eval-e）保证状态/会话互不污染；
知识库索引只读，不需要每次重建。
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_eval import DEFAULT_BASE, evaluate  # noqa: E402

GROUPS = [
    ('A', 'A 无记忆', {'CODEMEM_FEATURE_MEMORY': 'false', 'CODEMEM_FEATURE_RAG': 'false',
                       'CODEMEM_FEATURE_STATE': 'false', 'CODEMEM_FEATURE_DUAL': 'false'}),
    ('B', 'B 仅工作记忆', {'CODEMEM_FEATURE_MEMORY': 'true', 'CODEMEM_FEATURE_RAG': 'false',
                          'CODEMEM_FEATURE_STATE': 'false', 'CODEMEM_FEATURE_DUAL': 'false'}),
    ('C', 'C + RAG', {'CODEMEM_FEATURE_MEMORY': 'true', 'CODEMEM_FEATURE_RAG': 'true',
                      'CODEMEM_FEATURE_STATE': 'false', 'CODEMEM_FEATURE_DUAL': 'false'}),
    ('D', 'D + 状态', {'CODEMEM_FEATURE_MEMORY': 'true', 'CODEMEM_FEATURE_RAG': 'true',
                       'CODEMEM_FEATURE_STATE': 'true', 'CODEMEM_FEATURE_DUAL': 'false'}),
    ('E', 'E + 双角色', {'CODEMEM_FEATURE_MEMORY': 'true', 'CODEMEM_FEATURE_RAG': 'true',
                         'CODEMEM_FEATURE_STATE': 'true', 'CODEMEM_FEATURE_DUAL': 'true'}),
]

METRIC_LABELS = [
    ('recall_at_5', 'Recall@5'),
    ('keyword_hit', '引用关键词命中'),
    ('mode_accuracy', '路由准确率'),
    ('reviewer_coverage', 'Reviewer 覆盖'),
    ('memory_recall', '工作记忆召回'),
    ('state_accuracy', '状态准确率'),
    ('first_token_p95_ms', '首 token p95(ms)'),
    ('avg_tokens', '平均 token'),
]


def wait_health(base, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base + '/actuator/health', timeout=3) as response:
                body = json.load(response)
            if body.get('status') == 'UP':
                return True
        except Exception:  # noqa: BLE001 - 启动期连接失败属于预期
            pass
        time.sleep(2)
    return False


def start_app(jar, port, extra_env, log_path):
    env = dict(os.environ)
    env.update({
        'SERVER_PORT': str(port),
        'EMBEDDING_PROVIDER': 'hash',
        'CODEMEM_AI_MOCK': 'true',
    })
    env.update(extra_env)
    log = open(log_path, 'w', encoding='utf-8')
    process = subprocess.Popen(
        ['java', '-jar', os.path.abspath(jar)],
        cwd=os.path.dirname(os.path.abspath(jar)),
        env=env,
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    return process, log


def stop_app(process, log):
    try:
        process.terminate()
        process.wait(timeout=25)
    except Exception:  # noqa: BLE001
        try:
            process.kill()
        except Exception:  # noqa: BLE001
            pass
    finally:
        log.close()


def format_cell(value):
    if value is None:
        return '—'
    if isinstance(value, bool):
        return '是' if value else '否'
    return str(value)


def metric_table(results):
    header = '| 组 | 样本 | ' + ' | '.join(label for _, label in METRIC_LABELS) + ' |'
    separator = '| --- | --- | ' + ' | '.join('---' for _ in METRIC_LABELS) + ' |'
    lines = [header, separator]
    for name, metrics in results.items():
        cells = [name, str(metrics['items'])]
        for key, _ in METRIC_LABELS:
            cells.append(format_cell(metrics.get(key)))
        lines.append('| ' + ' | '.join(cells) + ' |')
    return '\n'.join(lines)


def type_table(by_type):
    lines = ['| 类型 | 样本 | 路由准确率 | Recall@5 | 状态准确率 | 首 token p95(ms) |',
             '| --- | --- | --- | --- | --- | --- |']
    for item_type, data in by_type.items():
        lines.append(
            f"| {item_type} | {data['items']} | {format_cell(data['mode_accuracy'])} | "
            f"{format_cell(data['recall_at_5'])} | {format_cell(data['state_accuracy'])} | "
            f"{format_cell(data['first_token_p95_ms'])} |"
        )
    return '\n'.join(lines)


def build_report(results, details, jar, limit, elapsed_seconds):
    lines = [
        '# 码忆 CodeMem 评测报告',
        '',
        f"- 生成时间：{time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 被测包：`{os.path.basename(jar)}`",
        f"- 黄金集：`tools/eval/golden.jsonl`，共 {limit} 条（5 类 × 6 条）",
        '- 运行模式：Mock 对话引擎 + 本地 hash embedding（不依赖外部 Key）',
        '- 数据隔离：每组使用独立 learnerId（eval-a…eval-e），状态/会话/工作记忆互不污染；知识库只读索引共用',
        f'- 总耗时：约 {elapsed_seconds:.0f}s',
        '',
        '## 1. 消融对比（30 条全量）',
        '',
        metric_table(results),
        '',
        '说明：`—` 表示该组按设计未启用对应能力，指标不适用（而非 0 分）；A/B/C 组的状态准确率记 0.0，'
        '表示未启用状态抽取、`nextActions` / `currentBlocker` 始终为空，因此判定为「未满足标注」。',
        '',
        '## 2. 分类明细（E 组：完整能力）',
        '',
        type_table(details['E']['by_type']),
        '',
        '## 3. 指标口径',
        '',
        '- **Recall@5**：期望笔记是否出现在前 5 条引用；样本为 6 条笔记事实题。',
        '- **引用关键词命中**：引用片段里是否出现标注关键词；是 Recall 的补充代理指标。',
        '- **路由准确率**：`agent_run.workflow` 是否与标注的 SINGLE/DUAL 一致。',
        '- **Reviewer 覆盖**：标注为 DUAL 的题目是否真的产生 reviewer 步骤。',
        '- **工作记忆召回**：跨会话题是否带上历史轮次（`done.memoryCount > 0`）。',
        '- **状态准确率**：抽取后 `nextActions` / `currentBlocker` 是否与人工标注一致。',
        '- **首 token 延迟 p95**：SSE 请求开始到首个 `token` 事件；Mock 引擎，仅反映链路开销。',
        '- **平均 token**：来自 `agent_run.prompt_tokens + completion_tokens`，缺失时按字符数 / 1.5 估算。',
        '',
        '## 4. 未覆盖项与已知局限',
        '',
        '1. **引用准确率的人工抽检未做**：规范要求人工抽检 20 条引用块是否真的支撑答案，本轮只给出自动代理指标（路径命中 + 关键词命中），语义支撑需人工复核。',
        '2. **答案质量未评测**：Mock 引擎返回固定骨架，无法反映真实回答质量；接入真实模型后需补答案正确性与可执行性评测。',
        '3. **token 口径**：Mock 模式没有真实 usage，token 为本地估算或 trace 记录值，与真实计费口径可能有偏差。',
        '4. **消融未覆盖长期记忆**：`memory_item` 尚未实现（阶段 E），本报告的「记忆」只包含工作记忆与画像。',
        '5. **评测集与实现同源**：黄金集取自 `E:\\Note\\知识库` 的公开笔记事实，不是训练数据，但与被测索引同源，Recall 指标偏乐观；需补充外部问题验证泛化。',
        '',
        '## 5. 复现方式',
        '',
        '```powershell',
        '# 单组（需先启动后端）',
        'python tools/eval/run_eval.py --base http://127.0.0.1:8080 --learner eval --project java-backend --out tools/eval/results/single.json',
        '',
        '# 五组消融（脚本自动起停后端，端口 18080）',
        'python tools/eval/ablation.py --jar target/codemem-0.1.0-SNAPSHOT.jar --out tools/eval/report.md',
        '```',
        '',
    ]
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', default='target/codemem-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'report.md'))
    parser.add_argument('--port', type=int, default=18080)
    parser.add_argument('--limit', type=int, default=None)
    args = parser.parse_args()

    jar = os.path.abspath(args.jar)
    if not os.path.exists(jar):
        print(f'jar 不存在：{jar}')
        return 1

    base = f'http://127.0.0.1:{args.port}'
    results_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'results')
    os.makedirs(results_dir, exist_ok=True)
    logs_dir = os.path.join(results_dir, 'logs')
    os.makedirs(logs_dir, exist_ok=True)

    started = time.time()
    results = {}
    details = {}
    for code, label, env in GROUPS:
        print(f'=== 启动 {label} ===')
        process, log = start_app(jar, args.port, env, os.path.join(logs_dir, f'{code}.log'))
        try:
            if not wait_health(base):
                print(f'{label} 启动失败，见 {logs_dir}/{code}.log')
                return 1
            outcome = evaluate(base, learner=f'eval-{code.lower()}', project='java-backend', limit=args.limit)
            results[code] = outcome['metrics']
            details[code] = outcome
            with open(os.path.join(results_dir, f'{code}.json'), 'w', encoding='utf-8') as handle:
                json.dump(outcome, handle, ensure_ascii=False, indent=2)
            print(f'{label} 完成：{outcome["metrics"]}')
        finally:
            stop_app(process, log)
            time.sleep(3)

    limit = args.limit or len(details['E']['items'])
    report = build_report(results, details, jar, limit, time.time() - started)
    with open(args.out, 'w', encoding='utf-8') as handle:
        handle.write(report)
    print(f'报告已写入：{args.out}')
    return 0


if __name__ == '__main__':
    sys.exit(main())