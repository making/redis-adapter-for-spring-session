package com.example.backend;

import am.ik.redis.adapter.boot.KeyValueStoreFactory;
import am.ik.redis.adapter.store.KeyValueStore;

// The region between the markers below is quoted verbatim in README.md. What it creates,
// MyKeyValueStore, stands in for the store a backend author would write.
// tag::backend-factory[]
public class MyKeyValueStoreFactory implements KeyValueStoreFactory {

	@Override
	public String name() {
		return "my-backend";
	}

	@Override
	public KeyValueStore create(int databaseIndex) {
		return new MyKeyValueStore(databaseIndex);
	}

}
// end::backend-factory[]
