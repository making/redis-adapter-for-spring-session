package am.ik.redis.adapter.boot;

/**
 * Counts what a backend was asked for while something ran, for the notes beside a
 * measurement.
 *
 * <p>
 * A timing on its own says how long something took; how many round trips it took is what
 * says why, and it is the number a design is argued about in. Only a backend can answer
 * it, and only some backends can answer it at all — a store in this process makes no
 * calls to count — so the harness asks through this and reports whatever comes back.
 */
public interface CallCounter {

	/** The counter of a backend that makes no calls worth counting. */
	CallCounter NONE = () -> new Snapshot() {
		@Override
		public String summary() {
			return "";
		}

		@Override
		public String perOperation(int operations) {
			return "";
		}
	};

	/**
	 * Marks where counting starts.
	 * @return what the counts are read against afterwards
	 */
	Snapshot begin();

	/**
	 * Where a count began, and what has happened since.
	 */
	interface Snapshot {

		/**
		 * Returns what the backend was asked for since this snapshot, as one line.
		 * @return the summary, empty for a backend that counts nothing
		 */
		String summary();

		/**
		 * Returns what the backend was asked per operation since this snapshot.
		 * @param operations how many operations ran
		 * @return the summary, empty for a backend that counts nothing
		 */
		String perOperation(int operations);

	}

}
