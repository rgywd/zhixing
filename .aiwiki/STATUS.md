---
source_commit: faa1320682d60db0cf30f821d05ed660197e00ed
generated: 2026-07-21
---

# 项目进展（按 commit）

项目近期聚焦于将 Codex 原生化电话线能力全面落地，并进行了多搜索提供商、语音合成、自动记忆维护等多项增强。当前版本 0.3.3 已稳定，修复了会话定位、UI 遮挡等数个体验问题，项目正从功能扩展转向体验打磨。

**功能**
- `7481430` deliver native Codex mode over App Server
- `ec6fadf` replace legacy stack with Codex phone lines
- `1563acd` restore native Codex chat parity
- `4e60e8c` add automatic profile maintenance
- `01a78d0` track tasks and archive sessions
- `44b1a77` teach phone sessions to report progress
- `e65c96b` add image attachments and repository catalog
- `4e68436` support multiple search providers
- `047023a` add Doubao search provider
- `361bbbc` 接入火山引擎 TTS
- `ff6a715` refine ask-user decision flow

**修复**
- `afd5826` 打开会话时定位最新进展
- `f362ddf` place hook profile before resume command
- `4e4de7a` keep session content below top bar
- `e849950` recover session stream after backgrounding
- `ad1310d` recover from app server request timeouts

**重构**
- `5719e8b` retire Volcengine Agent Plan

**杂项**
- `faa1320` release 0.3.3
- `e1bf556` release 0.3.1
- `94cd488` release 0.3.0
- `2548e06` 明确主干优先协作与发布规范
