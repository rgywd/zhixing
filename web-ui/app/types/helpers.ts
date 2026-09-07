import type { MessageNode } from "./conversation";
import type { UIMessage } from "./message";

export function getCurrentMessage(node: MessageNode): UIMessage {
  return node.messages[node.selectIndex] ?? node.messages[0];
}
