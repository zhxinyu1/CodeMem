-- 目标不再写死在画像里：改成“以笔记中的学习规划为准”。
-- 画像里的 goal 只作为用户自述参考，Prompt 会显式声明与笔记规划冲突时以笔记为准。

UPDATE learner_profile
SET goal = '目标以笔记中的学习规划为准（画像里暂未单独声明）',
    display_name = '学习者',
    updated_at = NOW(3)
WHERE learner_id = 'default';