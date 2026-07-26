package com.example.session;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What this instance of the example calls itself.
 *
 * <p>
 * The name is shown on the page, and it is there for one reason: with two instances
 * running, it is the only way to see that a session created against one of them is being
 * served by the other.
 *
 * @param instanceName the name this instance shows on the page
 */
@ConfigurationProperties(prefix = "example")
public record ExampleProperties(@DefaultValue("app-1") String instanceName) {
}
