package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.store.KeyValueStore;

/**
 * Creates the backend of one numbered database.
 *
 * <p>
 * This is what a backend module contributes to the server: a single bean, naming itself,
 * that the server calls once per database when {@code redis-adapter.backend} asks for
 * that name. How many databases there are, and everything else about the server, stays
 * the server's business.
 *
 * <p>
 * A database is an independent keyspace, so two calls must return stores that share no
 * keys. A backend that keeps its data somewhere shared — the interesting case, since that
 * is what lets several adapter replicas serve the same sessions — separates them by the
 * index it is given.
 *
 * <p>
 * Every registered factory is created whether or not it is the one selected, and the
 * selection is made afterwards. A factory must therefore hold no resource and open no
 * connection until {@link #create(int)} is called, or a backend nobody asked for would go
 * looking for a server nobody configured.
 */
public interface KeyValueStoreFactory {

	/**
	 * Returns the name this backend answers to, which is what
	 * {@code redis-adapter.backend} is matched against. Two backends must not share a
	 * name; the server refuses to start if they do.
	 * @return the backend's name, as an operator writes it
	 */
	String name();

	/**
	 * Creates the backend serving the given database.
	 * @param databaseIndex the database number, counting from {@code 0}
	 * @return the store that database's keys live in
	 */
	KeyValueStore create(int databaseIndex);

}
