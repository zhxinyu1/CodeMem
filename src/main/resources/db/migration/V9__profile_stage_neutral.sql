-- 默认学习画像的阶段改为中性表述，避免绑定具体笔记主题。
-- V2 已经应用过，直接改它会触发 Flyway checksum 失配，因此用独立迁移更新存量记录。
UPDATE learner_profile
SET stage = '数据结构基础',
    updated_at = NOW(3)
WHERE stage = '数据结构 / 红黑树';