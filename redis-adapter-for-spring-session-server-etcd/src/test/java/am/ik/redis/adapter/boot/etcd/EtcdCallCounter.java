package am.ik.redis.adapter.boot.etcd;

import am.ik.redis.adapter.boot.CallCounter;

/**
 * Counts what etcd was asked, from etcd's own {@code /metrics}.
 *
 * <p>
 * Read from the cluster rather than from the code, because what the code appears to issue
 * and what a raft cluster is actually made to do are two different numbers, and the
 * second one is what a session write costs.
 *
 * @param endpoint the client URL of the etcd to scrape
 */
record EtcdCallCounter(String endpoint) implements CallCounter {

	@Override
	public Snapshot begin() {
		EtcdMetrics before = EtcdMetrics.scrape(this.endpoint);
		return new Snapshot() {
			@Override
			public String summary() {
				return spent().callSummary();
			}

			@Override
			public String perOperation(int operations) {
				return spent().callsPerOperation(operations);
			}

			private EtcdMetrics spent() {
				return EtcdMetrics.scrape(EtcdCallCounter.this.endpoint).since(before);
			}
		};
	}

}
