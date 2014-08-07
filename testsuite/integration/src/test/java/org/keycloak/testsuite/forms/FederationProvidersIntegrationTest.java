package org.keycloak.testsuite.forms;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runners.MethodSorters;
import org.keycloak.Config;
import org.keycloak.OAuth2Constants;
import org.keycloak.federation.ldap.LDAPFederationProvider;
import org.keycloak.federation.ldap.LDAPFederationProviderFactory;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.Constants;
import org.keycloak.models.UserCredentialValueModel;
import org.keycloak.models.UserFederationProvider;
import org.keycloak.models.UserFederationProviderModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.TokenManager;
import org.keycloak.testutils.LDAPEmbeddedServer;
import org.keycloak.testsuite.LDAPTestUtils;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.testsuite.OAuthClient;
import org.keycloak.testsuite.pages.AccountPasswordPage;
import org.keycloak.testsuite.pages.AccountUpdateProfilePage;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.pages.RegisterPage;
import org.keycloak.testsuite.rule.KeycloakRule;
import org.keycloak.testsuite.rule.LDAPRule;
import org.keycloak.testsuite.rule.WebResource;
import org.keycloak.testsuite.rule.WebRule;
import org.openqa.selenium.WebDriver;

import java.util.HashMap;
import java.util.Map;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FederationProvidersIntegrationTest {

    private static LDAPRule ldapRule = new LDAPRule();

    private static Map<String,String> ldapConfig = null;

    private static UserFederationProviderModel ldapModel = null;

    private static KeycloakRule keycloakRule = new KeycloakRule(new KeycloakRule.KeycloakSetup() {

        @Override
        public void config(RealmManager manager, RealmModel adminstrationRealm, RealmModel appRealm) {
            addUser(manager.getSession(), appRealm, "mary", "mary@test.com", "password-app");
            addUser(manager.getSession(), adminstrationRealm, "mary-admin", "mary@admin.com", "password-admin");

            LDAPEmbeddedServer ldapServer = ldapRule.getEmbeddedServer();
            ldapConfig = new HashMap<String,String>();
            ldapConfig.put(LDAPConstants.CONNECTION_URL, ldapServer.getConnectionUrl());
            ldapConfig.put(LDAPConstants.BASE_DN, ldapServer.getBaseDn());
            ldapConfig.put(LDAPConstants.BIND_DN, ldapServer.getBindDn());
            ldapConfig.put(LDAPConstants.BIND_CREDENTIAL, ldapServer.getBindCredential());
            ldapConfig.put(LDAPConstants.USER_DN_SUFFIX, ldapServer.getUserDnSuffix());
            String vendor = ldapServer.getVendor();
            ldapConfig.put(LDAPConstants.VENDOR, vendor);
            ldapConfig.put(LDAPFederationProvider.SYNC_REGISTRATIONS, "true");
            ldapConfig.put(LDAPFederationProvider.EDIT_MODE, UserFederationProvider.EditMode.WRITABLE.toString());



            ldapModel = appRealm.addUserFederationProvider(LDAPFederationProviderFactory.PROVIDER_NAME, ldapConfig, 0, "test-ldap");

            // Configure LDAP
            ldapRule.getEmbeddedServer().setupLdapInRealm(appRealm);
            LDAPTestUtils.setLdapPassword(ldapConfig, "johnkeycloak", "password");
        }
    });

    @ClassRule
    public static TestRule chain = RuleChain
            .outerRule(ldapRule)
            .around(keycloakRule);

    @Rule
    public WebRule webRule = new WebRule(this);

    @WebResource
    protected OAuthClient oauth;

    @WebResource
    protected WebDriver driver;

    @WebResource
    protected AppPage appPage;

    @WebResource
    protected RegisterPage registerPage;

    @WebResource
    protected LoginPage loginPage;

    @WebResource
    protected AccountUpdateProfilePage profilePage;

    @WebResource
    protected AccountPasswordPage changePasswordPage;

    private static UserModel addUser(KeycloakSession session, RealmModel realm, String username, String email, String password) {
        UserModel user = session.users().addUser(realm, username);
        user.setEmail(email);
        user.setEnabled(true);

        UserCredentialModel creds = new UserCredentialModel();
        creds.setType(CredentialRepresentation.PASSWORD);
        creds.setValue(password);

        user.updateCredential(creds);
        return user;
    }

    @Test
    @Ignore
    public void runit() throws Exception {
        System.out.println("*** ldap config ***");
        for (Map.Entry<String, String> entry : ldapConfig.entrySet()) {
            System.out.println("key: " + entry.getKey() + " value: " + entry.getValue());
        }
        Thread.sleep(10000000);
    }

    @Test
    public void loginClassic() {
        loginPage.open();
        loginPage.login("mary", "password-app");

        Assert.assertEquals(AppPage.RequestType.AUTH_RESPONSE, appPage.getRequestType());
        Assert.assertNotNull(oauth.getCurrentQuery().get(OAuth2Constants.CODE));

    }

    @Test
    public void loginLdap() {
        loginPage.open();
        loginPage.login("johnkeycloak", "password");

        Assert.assertEquals(AppPage.RequestType.AUTH_RESPONSE, appPage.getRequestType());
        Assert.assertNotNull(oauth.getCurrentQuery().get(OAuth2Constants.CODE));

        profilePage.open();
        Assert.assertEquals("John", profilePage.getFirstName());
        Assert.assertEquals("Doe", profilePage.getLastName());
        Assert.assertEquals("john@email.org", profilePage.getEmail());
    }

    @Test
    public void XdeleteLink() { // make sure this happens after loginLdap()
        loginLdap();
        {
            KeycloakSession session = keycloakRule.startSession();
            try {
                RealmManager manager = new RealmManager(session);

                RealmModel appRealm = manager.getRealm("test");
                appRealm.removeUserFederationProvider(ldapModel);
                Assert.assertEquals(0, appRealm.getUserFederationProviders().size());
            } finally {
                keycloakRule.stopSession(session, true);
            }
        }
        loginPage.open();
        loginPage.login("johnkeycloak", "password");
        loginPage.assertCurrent();

        Assert.assertEquals("Invalid username or password.", loginPage.getError());

        {
            KeycloakSession session = keycloakRule.startSession();
            try {
                RealmManager manager = new RealmManager(session);

                RealmModel appRealm = manager.getRealm("test");
                ldapModel = appRealm.addUserFederationProvider(ldapModel.getProviderName(), ldapModel.getConfig(), ldapModel.getPriority(), ldapModel.getDisplayName());
            } finally {
                keycloakRule.stopSession(session, true);
            }
        }
        loginLdap();

    }

    @Test
    public void passwordChangeLdap() throws Exception {
        changePasswordPage.open();
        loginPage.login("johnkeycloak", "password");
        changePasswordPage.changePassword("password", "new-password", "new-password");

        Assert.assertEquals("Your password has been updated", profilePage.getSuccess());

        changePasswordPage.logout();

        loginPage.open();
        loginPage.login("johnkeycloak", "password");
        Assert.assertEquals("Invalid username or password.", loginPage.getError());

        loginPage.open();
        loginPage.login("johnkeycloak", "new-password");
        Assert.assertEquals(AppPage.RequestType.AUTH_RESPONSE, appPage.getRequestType());
    }

    @Test
    public void registerExistingLdapUser() {
        loginPage.open();
        loginPage.clickRegister();
        registerPage.assertCurrent();

        registerPage.register("firstName", "lastName", "email", "existing", "password", "password");

        registerPage.assertCurrent();
        Assert.assertEquals("Username already exists", registerPage.getError());
    }

    @Test
    public void registerUserLdapSuccess() {
        loginPage.open();
        loginPage.clickRegister();
        registerPage.assertCurrent();

        registerPage.register("firstName", "lastName", "email2", "registerUserSuccess2", "password", "password");
        Assert.assertEquals(AppPage.RequestType.AUTH_RESPONSE, appPage.getRequestType());

        KeycloakSession session = keycloakRule.startSession();
        RealmModel appRealm = session.realms().getRealmByName("test");
        UserModel user = session.users().getUserByUsername("registerUserSuccess2", appRealm);
        Assert.assertNotNull(user);
        Assert.assertNotNull(user.getFederationLink());
        Assert.assertEquals(user.getFederationLink(), ldapModel.getId());
        keycloakRule.stopSession(session, false);
    }

    @Test
    public void testReadonly() {
        KeycloakSession session = keycloakRule.startSession();
        RealmModel appRealm = session.realms().getRealmByName("test");

        UserFederationProviderModel model = new UserFederationProviderModel(ldapModel.getId(), ldapModel.getProviderName(), ldapModel.getConfig(), ldapModel.getPriority(), ldapModel.getDisplayName());
        model.getConfig().put(LDAPFederationProvider.EDIT_MODE, UserFederationProvider.EditMode.READ_ONLY.toString());
        appRealm.updateUserFederationProvider(model);
        UserModel user = session.users().getUserByUsername("johnkeycloak", appRealm);
        Assert.assertNotNull(user);
        Assert.assertNotNull(user.getFederationLink());
        Assert.assertEquals(user.getFederationLink(), ldapModel.getId());
        try {
            user.setEmail("error@error.com");
            Assert.fail("should fail");
        } catch (Exception e) {

        }
        try {
            user.setLastName("Berk");
            Assert.fail("should fail");
        } catch (Exception e) {

        }
        try {
            user.setFirstName("Bilbo");
            Assert.fail("should fail");
        } catch (Exception e) {

        }
        try {
            UserCredentialModel cred = UserCredentialModel.password("poop");
            user.updateCredential(cred);
            Assert.fail("should fail");
        } catch (Exception e) {

        }
        session.getTransaction().rollback();
        session.close();
        session = keycloakRule.startSession();
        appRealm = session.realms().getRealmByName("test");
        Assert.assertEquals(UserFederationProvider.EditMode.WRITABLE.toString(), appRealm.getUserFederationProviders().get(0).getConfig().get(LDAPFederationProvider.EDIT_MODE));
        keycloakRule.stopSession(session, false);
    }

    @Test
    public void testUnsynced() {
        KeycloakSession session = keycloakRule.startSession();
        RealmModel appRealm = session.realms().getRealmByName("test");

        UserFederationProviderModel model = new UserFederationProviderModel(ldapModel.getId(), ldapModel.getProviderName(), ldapModel.getConfig(), ldapModel.getPriority(), ldapModel.getDisplayName());
        model.getConfig().put(LDAPFederationProvider.EDIT_MODE, UserFederationProvider.EditMode.UNSYNCED.toString());
        appRealm.updateUserFederationProvider(model);
        UserModel user = session.users().getUserByUsername("johnkeycloak", appRealm);
        Assert.assertNotNull(user);
        Assert.assertNotNull(user.getFederationLink());
        Assert.assertEquals(user.getFederationLink(), ldapModel.getId());

        UserCredentialModel cred = UserCredentialModel.password("candy");
        user.updateCredential(cred);
        UserCredentialValueModel userCredentialValueModel = user.getCredentialsDirectly().get(0);
        Assert.assertEquals(UserCredentialModel.PASSWORD, userCredentialValueModel.getType());
        Assert.assertTrue(session.users().validCredentials(appRealm, user, cred));
        session.getTransaction().rollback();
        session.close();
        session = keycloakRule.startSession();
        appRealm = session.realms().getRealmByName("test");
        Assert.assertEquals(UserFederationProvider.EditMode.WRITABLE.toString(), appRealm.getUserFederationProviders().get(0).getConfig().get(LDAPFederationProvider.EDIT_MODE));
        keycloakRule.stopSession(session, false);
    }

}
