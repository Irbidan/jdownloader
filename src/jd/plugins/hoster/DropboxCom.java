package jd.plugins.hoster;

import java.io.IOException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.HashMap;
import java.util.Random;
import java.util.logging.Level;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.locale.JDL;

import org.appwork.utils.formatter.SizeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "dropbox.com" }, urls = { "https?://(www\\.)?(dl\\-web\\.dropbox\\.com/get/.*?w=[0-9a-f]+|([\\w]+:[\\w]+@)?api\\-content\\.dropbox\\.com/\\d+/files/.+|dropboxdecrypted\\.com/.+)" }, flags = { 2 })
public class DropboxCom extends PluginForHost {

    public DropboxCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.dropbox.com/pricing");
        this.setConfigElements();
    }

    public void correctDownloadLink(DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replace("dropboxdecrypted.com/", "dropbox.com/"));
        /* workaround for stable */
        link.setUrlDownload(link.getDownloadURL().replaceAll("#", "%23"));
    }

    private static final String             TYPE_S            = "https?://(www\\.)?dropbox\\.com/s/.+";
    private static Object                   LOCK              = new Object();
    private static HashMap<String, Cookies> accountMap        = new HashMap<String, Cookies>();
    private boolean                         TEMPUNAVAILABLE   = false;
    private boolean                         PASSWORDPROTECTED = false;
    private String                          DLLINK            = null;
    private static final String             DOWNLOAD_ZIP      = "DOWNLOAD_ZIP";

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        if (link.getBooleanProperty("decrypted")) {
            if (link.getBooleanProperty("offline", false)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            URLConnectionAdapter con = null;
            try {
                if (link.getDownloadURL().matches(TYPE_S)) {
                    this.br.setFollowRedirects(true);
                    DLLINK = link.getDownloadURL().replace("https://", "https://dl.");
                    con = this.br.openHeadConnection(DLLINK);
                    if (!con.getContentType().contains("html")) {
                        link.setProperty("directlink", con.getURL().toString());
                        link.setDownloadSize(con.getLongContentLength());
                        link.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(con).trim()));
                        return AvailableStatus.TRUE;
                    }
                    DLLINK = null;
                    /* Either offline or password protected */
                    this.br.getPage(link.getDownloadURL());
                    if (!this.br.getURL().contains("/password")) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                    PASSWORDPROTECTED = true;
                    return AvailableStatus.TRUE;
                }
            } finally {
                try {
                    con.disconnect();
                } catch (Throwable e) {
                }
            }

            DLLINK = link.getStringProperty("directlink", null);
            if (DLLINK == null) {
                DLLINK = link.getDownloadURL();
                if (con.getContentType().contains("html")) {
                    this.br.followConnection();
                }
            }
            this.br.setCookie("http://dropbox.com", "locale", "en");
            this.br.setFollowRedirects(true);
            try {
                con = br.openHeadConnection(DLLINK);
                if (con.getResponseCode() == 509) {
                    link.getLinkStatus().setStatusText("Temporarily unavailable");
                    TEMPUNAVAILABLE = true;
                    return AvailableStatus.TRUE;
                }
                if (!con.getContentType().contains("html")) {
                    link.setProperty("directlink", con.getURL().toString());
                    link.setDownloadSize(con.getLongContentLength());
                    link.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(con).trim()));
                    return AvailableStatus.TRUE;
                }
                this.br.followConnection();
            } finally {
                try {
                    con.disconnect();
                } catch (Throwable e) {
                }
            }

            if (this.br.containsHTML("(>Error \\(404\\)<|>Dropbox \\- 404<|>We can\\'t find the page you\\'re looking for|>The file you're looking for has been)") || this.br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            String filename = br.getRegex("content=\"([^<>/]*?)\" property=\"og:title\"").getMatch(0);
            if (filename == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final String filesize = br.getRegex("class=\"meta\">\\d+ (days|months) ago\\&nbsp;\\&middot;\\&nbsp; ([^<>\"]*?)</div><a").getMatch(1);
            if (filesize != null) {
                link.setDownloadSize(SizeFormatter.getSize(filesize.replace(",", ".")));
            }
            return AvailableStatus.TRUE;
        } else {
            return AvailableStatus.UNCHECKABLE;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        if (!account.getUser().matches(".+@.+\\..+")) {
            if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nBitte gib deine E-Mail Adresse ins Benutzername Feld ein!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlease enter your e-mail adress in the username field!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        }
        br.setDebug(true);
        try {
            login(account, true);
        } catch (final PluginException e) {
            account.setValid(false);
            throw e;
        }
        ai.setStatus("Registered (free) user");
        ai.setUnlimitedTraffic();
        account.setValid(true);
        return ai;
    }

    protected String generateNonce() {
        return Long.toString(new Random().nextLong());
    }

    protected String generateTimestamp() {
        return Long.toString(System.currentTimeMillis() / 1000L);
    }

    @Override
    public String getAGBLink() {
        return "https://www.dropbox.com/terms";
    }

    // TODO: Move into Utilities (It's here for a hack)
    // public class OAuth {

    @SuppressWarnings("deprecation")
    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        String passCode = link.getStringProperty("pass", null);
        if (link.getDownloadURL().endsWith("?dl=0") || link.getDownloadURL().endsWith("?dl=1")) {
            link.setUrlDownload(link.getDownloadURL().substring(0, link.getDownloadURL().length() - 5));
        }
        String t1 = new Regex(link.getDownloadURL(), "://(.*?):.*?@").getMatch(0);
        String t2 = new Regex(link.getDownloadURL(), "://.*?:(.*?)@").getMatch(0);
        if (t1 != null && t2 != null) {
            handlePremium(link, null);
            return;
        } else if (link.getBooleanProperty("decrypted")) {
            requestFileInformation(link);
            if (TEMPUNAVAILABLE) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 60 * 60 * 1000l);
            } else if (this.PASSWORDPROTECTED) {
                final Form pwform = this.br.getFormbyProperty("id", "password-form");
                if (pwform == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                pwform.setAction("https://www.dropbox.com/sm/auth");
                if (passCode == null) {
                    passCode = getUserInput("Password?", link);
                }
                pwform.put("password", passCode);
                this.br.submitForm(pwform);
                if (this.br.getURL().contains("/password") || getJson("error") != null) {
                    link.setProperty("pass", Property.NULL);
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
                }
                this.br.getPage(link.getDownloadURL());
                link.setProperty("pass", passCode);
                DLLINK = this.br.getURL() + "?dl=1";
            }
            if (link.getStringProperty("directlink", null) == null) {
                DLLINK = link.getDownloadURL() + "?dl=1";
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, DLLINK, false, 1);
            if (dl.getConnection().getContentType().contains("html")) {
                logger.warning("Directlink leads to HTML code...");
                br.followConnection();
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl.startDownload();
        } else {
            throw new PluginException(LinkStatus.ERROR_FATAL, "You can only download files from your own account!");
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink downloadLink, final Account account) throws Exception {
        String dlURL = downloadLink.getDownloadURL();
        boolean resume = true;
        if (dlURL.matches(".*api-content.dropbox.com.*")) {
            /* api downloads via tokens */
            resume = false;
            try {
                /* Decrypt oauth token and secret */
                byte[] crypted_oauth_consumer_key = org.appwork.utils.encoding.Base64.decode("1lbl8Ts5lNJPxMOBzazwlg==");
                byte[] crypted_oauth_consumer_secret = org.appwork.utils.encoding.Base64.decode("cqqyvFx1IVKNPennzVKUnw==");
                byte[] iv = new byte[] { (byte) 0xF0, 0x0B, (byte) 0xAA, (byte) 0x69, 0x42, (byte) 0xF0, 0x0B, (byte) 0xAA };
                byte[] secretKey = (new Regex(dlURL, "passphrase=([^&]+)").getMatch(0).substring(0, 8)).getBytes("UTF-8");

                SecretKey key = new SecretKeySpec(secretKey, "DES");
                AlgorithmParameterSpec paramSpec = new IvParameterSpec(iv);
                Cipher dcipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
                dcipher.init(Cipher.DECRYPT_MODE, key, paramSpec);
                String oauth_consumer_key = new String(dcipher.doFinal(crypted_oauth_consumer_key), "UTF-8");
                String oauth_token_secret = new String(dcipher.doFinal(crypted_oauth_consumer_secret), "UTF-8");

                /* remove existing tokens from url */
                dlURL = dlURL.replaceFirst("://[\\w:]+@", "://");
                /* remove passphrase from url */
                dlURL = dlURL.replaceFirst("[\\?&]passphrase=[^&]+", "");
                String t1 = new Regex(downloadLink.getDownloadURL(), "://(.*?):.*?@").getMatch(0);
                String t2 = new Regex(downloadLink.getDownloadURL(), "://.*?:(.*?)@").getMatch(0);
                if (t1 == null) {
                    t1 = account.getUser();
                }
                if (t2 == null) {
                    t2 = account.getPass();
                }
                dlURL = signOAuthURL(dlURL, oauth_consumer_key, oauth_token_secret, t1, t2);
            } catch (PluginException e) {
                throw e;
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        } else {
            /* website downloads */
            login(account, false);
            if (!dlURL.contains("?dl=1") && !dlURL.contains("&dl=1")) {
                dlURL = dlURL + "&dl=1";
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dlURL, resume, 1);
        final URLConnectionAdapter con = dl.getConnection();
        if (con.getResponseCode() != 200) {
            con.disconnect();
            if (con.getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (con.getResponseCode() == 401) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    /* is only used for website logins */
    private void login(final Account account, boolean refresh) throws IOException, PluginException {
        boolean ok = false;
        synchronized (LOCK) {
            setBrowserExclusive();
            br.setFollowRedirects(true);
            if (refresh == false) {
                Cookies accCookies = accountMap.get(account.getUser());
                if (accCookies != null) {
                    br.getCookies("https://www.dropbox.com").add(accCookies);
                    return;
                }
            }
            try {
                br.getPage("https://www.dropbox.com/login");
                final String lang = System.getProperty("user.language");
                final String t = br.getRegex("type=\"hidden\" name=\"t\" value=\"([^<>\"]*?)\"").getMatch(0);
                if (t == null) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin defekt, bitte den JDownloader Support kontaktieren!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin broken, please contact the JDownloader Support!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.postPage("/sso_state", "is_xhr=true&t=" + t + "&email=" + Encoding.urlEncode(account.getUser()));
                br.postPage("/ajax_login", "recaptcha_response_field=&recaptcha_public_key=6LeAbPQSAAAAAB_-BzhpAZbgz51jHD2pGIKsM6L0&remember_me=True&is_xhr=true&t=" + t + "&cont=%2F&require_role=&signup_data=&signup_tag=&login_email=" + Encoding.urlEncode(account.getUser()) + "&login_password=" + Encoding.urlEncode(account.getPass()));
                if (br.getCookie("https://www.dropbox.com", "jar") == null || !"OK".equals(getJson("status"))) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                ok = true;
            } finally {
                if (ok) {
                    accountMap.put(account.getUser(), br.getCookies("https://www.dropbox.com"));
                } else {
                    accountMap.remove(account.getUser());
                }
            }
        }

    }

    /**
     * Wrapper<br/>
     * Tries to return value of key from JSon response, from default 'br' Browser.
     *
     * @author raztoki
     * */
    private String getJson(final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJson(br.toString(), key);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    /**
     * Sign an OAuth GET request with HMAC-SHA1 according to OAuth Core spec 1.0
     *
     * @return new url including signature
     * @throws PluginException
     */
    public/* static */String signOAuthURL(String url, String oauth_consumer_key, String oauth_consumer_secret, String oauth_token, String oauth_token_secret) throws PluginException {
        // At first, we remove all OAuth parameters from the url. We add
        // them
        // all manually.
        url = url.replaceAll("[\\?&]oauth_\\w+?=[^&]+", "");
        url += (url.contains("?") ? "&" : "?") + "oauth_consumer_key=" + oauth_consumer_key;
        url += "&oauth_nonce=" + generateNonce();

        url += "&oauth_signature_method=HMAC-SHA1";
        url += "&oauth_timestamp=" + generateTimestamp();
        url += "&oauth_token=" + oauth_token;
        url += "&oauth_version=1.0";

        String signatureBaseString = Encoding.urlEncode(url);
        signatureBaseString = signatureBaseString.replaceFirst("%3F", "&");
        // See OAuth 1.0 spec Appendix A.5.1
        signatureBaseString = "GET&" + signatureBaseString;

        String keyString = oauth_consumer_secret + "&" + oauth_token_secret;
        String signature = "";
        try {
            Mac mac = Mac.getInstance("HmacSHA1");

            SecretKeySpec secret = new SecretKeySpec(keyString.getBytes("UTF-8"), "HmacSHA1");
            mac.init(secret);
            byte[] digest = mac.doFinal(signatureBaseString.getBytes("UTF-8"));
            signature = new String(org.appwork.utils.encoding.Base64.encodeToString(digest, false)).trim();

        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        url += "&oauth_signature=" + Encoding.urlEncode(signature);
        return url;
    }

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), DropboxCom.DOWNLOAD_ZIP, JDL.L("plugins.hoster.DropboxCom.DownloadZip", "Download .zip file of all files in the folder?")).setDefaultValue(false));
    }

}