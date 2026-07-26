package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.store.KeyValueStore;

/**
 * Creates the backend of one numbered database.
 *
 * <p>
 * This is the whole of what a backend contributes to the server: a single bean, naming
 * itself, that the server calls once per database. How many databases there are, and
 * everything else about the server, stays the server's business.
 *
 * <p>
 * A server is built around exactly one backend and refuses to start with any other
 * number, so a deployment chooses its backend by choosing the jar it runs. Supporting a
 * store this project has never heard of means a module of its own — the server module,
 * this bean, and a main class — and nothing in the server has to be changed or even
 * rebuilt for it.
 *
 * <p>
 * A database is an independent keyspace, so two calls must return stores that share no
 * keys. A backend that keeps its data somewhere shared — the interesting case, since that
 * is what lets several adapter replicas serve the same sessions — separates them by the
 * index it is given.
 *
 * <p>
 * The factory is created while the application starts and is asked for its stores after
 * everything is bound, so it must hold no resource and open no connection until
 * {@link #create(int)} is called.
 */
public interface KeyValueStoreFactory {

	/**
	 * Returns the name of the backend, which is what the server logs and what a reader of
	 * the logs uses to tell one deployment from another.
	 * @return the backend's name, as an operator would write it
	 */
	String name();

	/**
	 * Creates the backend serving the given database.
	 * @param databaseIndex the database number, counting from {@code 0}
	 * @return the store that database's keys live in
	 */
	KeyValueStore create(int databaseIndex);

}
