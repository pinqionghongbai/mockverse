-- pgvector 扩展（D10 起依赖）
-- 镜像 pgvector/pgvector:pg16 自带扩展文件，但**必须显式 CREATE EXTENSION**，
-- 否则建表时 vector(512) 类型不可用。init 脚本只在数据目录为空时执行一次。
CREATE EXTENSION IF NOT EXISTS vector;
