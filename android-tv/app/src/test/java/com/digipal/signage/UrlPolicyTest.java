package com.digipal.signage;

  import static org.junit.Assert.assertFalse;
  import static org.junit.Assert.assertTrue;

  import org.junit.Test;
  import org.junit.runner.RunWith;
  import org.robolectric.RobolectricTestRunner;

  @RunWith(RobolectricTestRunner.class)
  public class UrlPolicyTest {

      @Test
      public void httpsIsAlwaysAllowed() {
          assertTrue(UrlPolicy.isAllowedServerUrl("https://www.digipalsignage.com"));
          assertTrue(UrlPolicy.isAllowedServerUrl("https://8.8.8.8/path"));
      }

      @Test
      public void httpIsAllowedOnlyForPrivateHosts() {
          assertTrue(UrlPolicy.isAllowedServerUrl("http://192.168.1.50:5000"));
          assertTrue(UrlPolicy.isAllowedServerUrl("http://10.0.0.5"));
          assertTrue(UrlPolicy.isAllowedServerUrl("http://localhost:5000"));
          assertTrue(UrlPolicy.isAllowedServerUrl("http://127.0.0.1"));
      }

      @Test
      public void httpIsRejectedForPublicHosts() {
          assertFalse(UrlPolicy.isAllowedServerUrl("http://www.digipalsignage.com"));
          assertFalse(UrlPolicy.isAllowedServerUrl("http://8.8.8.8"));
      }

      @Test
      public void rejectsMalformedOrCredentialedUrls() {
          assertFalse(UrlPolicy.isAllowedServerUrl(null));
          assertFalse(UrlPolicy.isAllowedServerUrl(""));
          assertFalse(UrlPolicy.isAllowedServerUrl("not a url"));
          assertFalse(UrlPolicy.isAllowedServerUrl("ftp://192.168.1.1"));
          assertFalse(UrlPolicy.isAllowedServerUrl("https://user:pass@www.digipalsignage.com"));
      }

      @Test
      public void isPrivateHostRecognizesRfc1918Ranges() {
          assertTrue(UrlPolicy.isPrivateHost("10.1.2.3"));
          assertTrue(UrlPolicy.isPrivateHost("172.16.0.1"));
          assertTrue(UrlPolicy.isPrivateHost("172.31.255.255"));
          assertTrue(UrlPolicy.isPrivateHost("192.168.0.1"));
          assertTrue(UrlPolicy.isPrivateHost("127.0.0.1"));
          assertTrue(UrlPolicy.isPrivateHost("localhost"));
          assertTrue(UrlPolicy.isPrivateHost("::1"));
          assertTrue(UrlPolicy.isPrivateHost("fd00::1"));
          assertTrue(UrlPolicy.isPrivateHost("[fe80::1]"));
      }

      @Test
      public void isPrivateHostRejectsPublicAddresses() {
          assertFalse(UrlPolicy.isPrivateHost("172.15.0.1"));
          assertFalse(UrlPolicy.isPrivateHost("172.32.0.1"));
          assertFalse(UrlPolicy.isPrivateHost("8.8.8.8"));
          assertFalse(UrlPolicy.isPrivateHost("www.digipalsignage.com"));
          assertFalse(UrlPolicy.isPrivateHost(null));
      }
  }
  