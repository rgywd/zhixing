/**
 * 知行两档执行策略 → codex app-server v2 参数映射。
 * 档位在建会话时一锤定音（AGENT_DESIGN.md 8.3 同款决策适用于 Codex 通道 P1）。
 */
import type { PermissionMode } from '../types.js'

export interface CodexExecutionPolicy {
  /** thread/start.approvalPolicy，kebab-case wire 值 */
  approvalPolicy: 'untrusted' | 'never'
  /** thread/start.sandbox（SandboxMode 简写），kebab-case wire 值 */
  sandbox: 'workspace-write' | 'danger-full-access'
  /** true 时不会有审批回调打到手机（完全访问档） */
  autoApprove: boolean
}

/**
 * @param hasHardLimits 存在硬性限制时，完全访问档不能用 `never`（没有审批回调可拦截），
 *   改为 `untrusted` 回调 + 未命中规则自动通过；沙箱仍全放开。
 */
export function resolveExecutionPolicy(
  mode: string | undefined,
  hasHardLimits = false,
): CodexExecutionPolicy {
  if (normalizeMode(mode) === 'bypassPermissions') {
    return hasHardLimits
      ? { approvalPolicy: 'untrusted', sandbox: 'danger-full-access', autoApprove: true }
      : { approvalPolicy: 'never', sandbox: 'danger-full-access', autoApprove: true }
  }
  return { approvalPolicy: 'untrusted', sandbox: 'workspace-write', autoApprove: false }
}

/** 手机端只有两档；其他值（历史数据/第三方客户端）一律落回普通档 */
export function normalizeMode(mode: string | undefined): PermissionMode {
  return mode === 'bypassPermissions' || mode === 'yolo' ? 'bypassPermissions' : 'default'
}
