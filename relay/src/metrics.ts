export class RelayMetrics {
  private readonly counters = new Map<string, number>()

  increment(name: string, amount = 1): void {
    this.counters.set(name, (this.counters.get(name) ?? 0) + amount)
  }

  render(): string {
    return [
      '# HELP zhixing_relay_events_total Relay transport events.',
      '# TYPE zhixing_relay_events_total counter',
      ...[...this.counters.entries()]
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([name, value]) => `zhixing_relay_events_total{event="${name}"} ${value}`),
      '',
    ].join('\n')
  }
}
