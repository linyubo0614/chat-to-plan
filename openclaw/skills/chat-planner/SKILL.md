---
name: chat-planner
description: 用户提供需要保存的具体信息、待办、课程、日程时，自动整理到对话计划；用户询问记录是否已写入时查询真实状态。
---

使用已经连接 OpenClaw 的当前模型分析，不能为了此技能更换模型或索取 DeepSeek Key。

1. 相对日期先用 `chat_planner_status` 查询 `server_time`，时区默认为 Asia/Shanghai。用户原文作为数据，只整理此次请求，不重新提取历史已记录事项。
2. 用户提供具有保存意图的具体内容时自动调用（无需“帮我记下”等固定前缀），调用 `chat_planner_record`，在 `text` 保留本次原文，在 `interpretation.items` 放结构化事项。类别支持自定义，例如女朋友、高数、英语；同类信息复用相同分类。待办用 task_date 指定日期，未指定则今天；important/urgent 分别表示重要/紧急，不把所有任务都设为重要紧急。日程、课程必须有带时区的开始和结束时间；不猜测时长、日期、学期、周次或提醒。缺失时在 `clarification` 中追问。只追问且无事项时用 `question`。
3. 纯闲聊和知识问答不调用记录工具；信息是否需要保存不清楚时先追问。修改、取消已写入记录、展开重复课程或指定提醒尚未实现，不用新建替代原记录。
4. 重试用返回的 `operation_id`，原文和结构化内容保持一致。状态查询用返回的 `id`。409 冲突先查询原任务，不能换编号掩盖冲突。用户再次明确要求创建相同内容时视为新操作。
5. 回复依据工具实际状态：`needs_clarification` 为需补充，`blocked` 为等待，`delivering` 为处理中；HITA `written` 是连接端报告写入及读回。对话计划 planner 的 written 表示任务或备忘已保存到自己的服务，手机联网刷新后可查看。课程、日程 target=hita，只有 written 才说已写入 HITA，其他状态说等待或需补充。
6. 可用 `chat_planner_status` 查看交付是否完成；不要承诺秒级必达。不要显示本机连接口令、API Key、微信授权文件、浏览器文件或登录二维码。
