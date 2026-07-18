export class RelayError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

export function conflict(code: string, message: string): never {
  throw new RelayError(409, code, message)
}

export function forbidden(code: string, message: string): never {
  throw new RelayError(403, code, message)
}

export function notFound(code: string, message: string): never {
  throw new RelayError(404, code, message)
}
