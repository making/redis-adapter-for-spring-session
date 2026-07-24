package am.ik.redis.adapter.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AuthenticatorTest {

	@Test
	void anOpenServerRequiresNothingAndAcceptsAnything() {
		Authenticator authenticator = Authenticator.open();

		assertThat(authenticator.isRequired()).isFalse();
		assertThat(authenticator.authenticate("default", "whatever")).isTrue();
		assertThat(authenticator.authenticate("someone", "")).isTrue();
	}

	@Test
	void aPasswordIsAcceptedForTheDefaultUser() {
		Authenticator authenticator = Authenticator.password("s3cret");

		assertThat(authenticator.isRequired()).isTrue();
		assertThat(authenticator.authenticate(Authenticator.DEFAULT_USERNAME, "s3cret")).isTrue();
	}

	@Test
	void aWrongPasswordIsRejected() {
		Authenticator authenticator = Authenticator.password("s3cret");

		assertThat(authenticator.authenticate(Authenticator.DEFAULT_USERNAME, "s3cre")).isFalse();
		assertThat(authenticator.authenticate(Authenticator.DEFAULT_USERNAME, "s3crets")).isFalse();
		assertThat(authenticator.authenticate(Authenticator.DEFAULT_USERNAME, "S3CRET")).isFalse();
		assertThat(authenticator.authenticate(Authenticator.DEFAULT_USERNAME, "")).isFalse();
	}

	@Test
	void aPasswordBelongsToItsUser() {
		Authenticator authenticator = Authenticator.password("s3cret");

		assertThat(authenticator.authenticate("someone-else", "s3cret")).isFalse();
	}

	@Test
	void aUserNameAndPasswordPairIsAcceptedTogetherOnly() {
		Authenticator authenticator = Authenticator.usernamePassword("session-app", "s3cret");

		assertThat(authenticator.authenticate("session-app", "s3cret")).isTrue();
		assertThat(authenticator.authenticate("session-app", "wrong")).isFalse();
		assertThat(authenticator.authenticate("default", "s3cret")).isFalse();
	}

	@Test
	void credentialsAreComparedAsBytesSoNonAsciiWorks() {
		Authenticator authenticator = Authenticator.password("パスワード");

		assertThat(authenticator.authenticate(Authenticator.DEFAULT_USERNAME, "パスワード")).isTrue();
		assertThat(authenticator.authenticate(Authenticator.DEFAULT_USERNAME, "パスワート")).isFalse();
	}

	@Test
	void anEmptyPasswordIsRejectedAtConfigurationTime() {
		assertThatIllegalArgumentException().isThrownBy(() -> Authenticator.password(""))
			.withMessage("password must not be empty");
	}

	@Test
	void anEmptyUserNameIsRejectedAtConfigurationTime() {
		assertThatIllegalArgumentException().isThrownBy(() -> Authenticator.usernamePassword("", "s3cret"))
			.withMessage("username must not be empty");
	}

	@Test
	void aPasswordIsNeverPartOfTheDescription() {
		assertThat(Authenticator.usernamePassword("session-app", "s3cret").toString())
			.isEqualTo("Authenticator.usernamePassword(session-app, ****)");
	}

}
