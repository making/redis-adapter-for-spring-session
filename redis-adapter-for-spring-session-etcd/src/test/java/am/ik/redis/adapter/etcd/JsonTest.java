package am.ik.redis.adapter.etcd;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JSON etcd's gateway actually writes, read back.
 *
 * <p>
 * The samples here are answers a real etcd gave, which is what the parser has to cope
 * with: 64-bit fields as strings, bytes as base64, and every field that holds its default
 * value simply absent.
 */
class JsonTest {

	@Test
	void aRangeResponseIsReadFieldByField() {
		String response = """
				{"header":{"cluster_id":"14841639068965178418","member_id":"10276657743932975437",\
				"revision":"7","raft_term":"2"},"kvs":[{"key":"L3QvYw==","create_revision":"7",\
				"mod_revision":"7","version":"1","value":"YzE=","lease":"7587896416692961541"}],"count":"1"}""";

		Map<String, Object> parsed = Json.parseObject(response);

		Map<String, Object> kv = Json.object(Json.array(parsed.get("kvs")).get(0));
		assertThat(kv).isNotNull();
		assertThat(Json.bytes(kv.get("key"))).isEqualTo("/t/c".getBytes(UTF_8));
		assertThat(Json.bytes(kv.get("value"))).isEqualTo("c1".getBytes(UTF_8));
		assertThat(Json.integer(kv.get("mod_revision"), 0)).isEqualTo(7L);
		assertThat(Json.integer(kv.get("lease"), 0)).isEqualTo(7587896416692961541L);
	}

	/**
	 * A key with no lease, an empty value and a transaction that did not succeed are all
	 * rendered by leaving the field out, so an absent field has to mean the default
	 * rather than a failure to parse.
	 */
	@Test
	void anAbsentFieldMeansItsDefault() {
		Map<String, Object> parsed = Json.parseObject("""
				{"header":{"revision":"4"},"kvs":[{"key":"L3QvYg==","mod_revision":"4"}]}""");

		Map<String, Object> kv = Json.object(Json.array(parsed.get("kvs")).get(0));
		assertThat(kv).isNotNull();
		assertThat(Json.integer(kv.get("lease"), 0)).isZero();
		assertThat(Json.bytes(kv.get("value"))).isNull();
		assertThat(Json.flag(parsed.get("succeeded"))).isFalse();
		assertThat(Json.array(parsed.get("prev_kvs"))).isEmpty();
		assertThat(Json.object(parsed.get("nothing"))).isNull();
		assertThat(Json.text(parsed.get("nothing"))).isNull();
	}

	@Test
	void aWatchEventIsReadWithWhatTheKeyHeld() {
		String response = """
				{"result":{"header":{"revision":"6"},"events":[{"type":"DELETE",\
				"kv":{"key":"L3QvYQ==","mod_revision":"6"},\
				"prev_kv":{"key":"L3QvYQ==","mod_revision":"3","value":"djE=","lease":"75"}}]}}""";

		Map<String, Object> result = Json.object(Json.parseObject(response).get("result"));

		assertThat(result).isNotNull();
		Map<String, Object> event = Json.object(Json.array(result.get("events")).get(0));
		assertThat(event).isNotNull();
		assertThat(Json.text(event.get("type"))).isEqualTo("DELETE");
		Map<String, Object> previous = Json.object(event.get("prev_kv"));
		assertThat(previous).isNotNull();
		assertThat(Json.bytes(previous.get("value"))).isEqualTo("v1".getBytes(UTF_8));
	}

	@Test
	void anErrorIsAnObjectWithACodeAndAMessage() {
		Map<String, Object> parsed = Json.parseObject("""
				{"code":9, "message":"etcdserver: authentication is not enabled"}""");

		assertThat(Json.integer(parsed.get("code"), -1)).isEqualTo(9L);
		assertThat(Json.text(parsed.get("message"))).isEqualTo("etcdserver: authentication is not enabled");
	}

	@Test
	void escapesAndNestingAreRead() {
		Map<String, Object> parsed = Json.parseObject("""
				{"a":"quote \\" backslash \\\\ newline \\n unicode \\u00e9","b":[1,-2.5,true,false,null],"c":{}}""");

		assertThat(Json.text(parsed.get("a"))).isEqualTo("quote \" backslash \\ newline \n unicode é");
		assertThat(Json.array(parsed.get("b"))).containsExactly(1.0d, -2.5d, Boolean.TRUE, Boolean.FALSE, Json.NULL);
		assertThat(Json.object(parsed.get("c"))).isEmpty();
	}

	@Test
	void whatIsNotAJsonObjectIsRefused() {
		assertThatThrownBy(() -> Json.parseObject("[1,2]")).isInstanceOf(EtcdException.class);
		assertThatThrownBy(() -> Json.parseObject("{} trailing")).isInstanceOf(EtcdException.class);
		assertThatThrownBy(() -> Json.parseObject("{\"a\":}")).isInstanceOf(EtcdException.class);
		assertThatThrownBy(() -> Json.parseObject("{\"a\"")).isInstanceOf(EtcdException.class);
	}

	// --- writing -----------------------------------------------------------------------

	@Test
	void aRequestIsWrittenTheWayTheGatewayExpectsIt() {
		String request = Json.write()
			.bytes("key", "/t/a".getBytes(UTF_8))
			.integer("lease", 7587896416692961541L)
			.flag("prev_kv", true)
			.text("target", "MOD")
			.toString();

		assertThat(request).isEqualTo("{\"key\":\"" + Base64.getEncoder().encodeToString("/t/a".getBytes(UTF_8))
				+ "\",\"lease\":\"7587896416692961541\",\"prev_kv\":true,\"target\":\"MOD\"}");
	}

	@Test
	void nestedObjectsAndArraysAreWritten() {
		String request = Json.write()
			.array("compare", List.of(Json.write().text("result", "EQUAL")))
			.object("create_request", Json.write().flag("prev_kv", true))
			.toString();

		assertThat(request).isEqualTo("{\"compare\":[{\"result\":\"EQUAL\"}],\"create_request\":{\"prev_kv\":true}}");
	}

	/**
	 * A password is the one thing written here that is not base64 or a number, and it is
	 * whatever an operator chose.
	 */
	@Test
	void textIsEscapedOnTheWayOut() {
		String password = "a\"b\\c\nd" + (char) 1;

		String request = Json.write().text("password", password).toString();

		assertThat(request).doesNotContain("\n").contains("\\u0001");
		assertThat(Json.text(Json.parseObject(request).get("password"))).isEqualTo(password);
	}

}
