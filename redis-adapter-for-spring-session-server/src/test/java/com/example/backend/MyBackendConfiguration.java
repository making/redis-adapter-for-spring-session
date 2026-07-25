package com.example.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The region between the markers below is quoted verbatim in README.md.
// tag::backend-registration[]
@Configuration(proxyBeanMethods = false)
public class MyBackendConfiguration {

	@Bean
	public MyKeyValueStoreFactory myKeyValueStoreFactory() {
		return new MyKeyValueStoreFactory();
	}

}
// end::backend-registration[]
