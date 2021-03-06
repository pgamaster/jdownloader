//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.IOException;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.JDHash;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "fileload.io" }, urls = { "https?://(?:www\\.)?fileloaddecrypted\\.io/[A-Za-z0-9]+/s/\\d+" }, flags = { 2 })
public class FileloadIo extends PluginForHost {

    public FileloadIo(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://fileload.io/premium");
    }

    @Override
    public String getAGBLink() {
        return "https://fileload.io/tos";
    }

    /* Notes: Also known as/before: netload.in, dateisenden.de */
    private static final String API_BASE                     = "https://api.fileload.io/";
    /* Connection stuff */
    private final boolean       FREE_RESUME                  = true;
    private final int           FREE_MAXCHUNKS               = 0;
    private final int           FREE_MAXDOWNLOADS            = 20;
    private final boolean       USE_API_LINKCHECK            = true;
    private final boolean       ACCOUNT_FREE_RESUME          = true;
    private final int           ACCOUNT_FREE_MAXCHUNKS       = 0;
    private final int           ACCOUNT_FREE_MAXDOWNLOADS    = 20;
    private final boolean       ACCOUNT_PREMIUM_RESUME       = true;
    private final int           ACCOUNT_PREMIUM_MAXCHUNKS    = 0;
    private final int           ACCOUNT_PREMIUM_MAXDOWNLOADS = 20;

    private String              account_auth_token           = null;
    private String              dllink                       = null;
    private boolean             server_issues                = false;
    private String              folderid                     = null;
    private String              linkid                       = null;

    public void correctDownloadLink(final DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replace("fileloaddecrypted.io/", "fileload.io/"));
    }

    public static Browser prepBRAPI(final Browser br) {
        br.getHeaders().put("User-Agent", "JDownloader");
        return br;
    }

    public static Browser prepBRWebsite(final Browser br) {
        return br;
    }

    /*
     * This hoster plugin is VERY basic because it is not clear whether this service will gain any popularity and maybe there will be an API
     * in the future!
     */
    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        dllink = null;
        server_issues = false;
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        folderid = getFolderid(link.getDownloadURL());
        linkid = getLinkid(link.getDownloadURL());
        if (USE_API_LINKCHECK) {
            /* TODO: Implement this! */
            prepBRAPI(this.br);
        } else {
            /* First let's see if the main folder is still online! */
            if (getFilenameProperty(link) == null) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            this.br.getPage("https://" + this.getHost() + "/" + folderid);
            if (mainlinkIsOffline(this.br)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            /* Main folder is online --> Check if the individual file is downloadable */
            /* Skip that pre-download-step as it does not help us in any way */
            // this.br.getPage("/index.php?id=5&f=attemptDownload&transfer_id=" + folderid + "&file_id=" + linkid +
            // "&download=false");
            getDllinkWebsite();
            URLConnectionAdapter con = null;
            try {
                /* Do NOT user HEAD connection here! */
                con = br.openGetConnection(dllink);
                if (!con.getContentType().contains("html")) {
                    link.setDownloadSize(con.getLongContentLength());
                    link.setFinalFileName(getFileNameFromHeader(con));
                } else {
                    server_issues = true;
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    private void getDllinkWebsite() throws IOException, PluginException {
        this.br.getPage("https://" + this.getHost() + "/index.php?id=5&f=attemptDownload&transfer_id=" + folderid + "&file_id=" + linkid + "&download=true");
        final String status = PluginJSonUtils.getJson(this.br, "status");
        if ("too_many_requests".equalsIgnoreCase(status)) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error 'Too many requests'", 3 * 60 * 1000l);
        }
        dllink = PluginJSonUtils.getJson(this.br, "link");
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    private String getFilenameProperty(final DownloadLink dl) {
        return dl.getStringProperty("directfilename", null);
    }

    public static boolean mainlinkIsOffline(final Browser br) {
        final boolean isOffline = br.getHttpConnection().getResponseCode() == 404 || br.containsHTML(">404 \\- Not Found|The requested page was not found");
        return isOffline;
    }

    private String getFolderid(final String url) {
        return new Regex(url, "fileload\\.io/([A-Za-z0-9]+)").getMatch(0);
    }

    private String getLinkid(final String url) {
        return new Regex(url, "(\\d+)$").getMatch(0);
    }

    /** TODO: 2016-06-02: Fix free mode!! */
    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        doFree(downloadLink, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink downloadLink, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        if (USE_API_LINKCHECK) {
            /* API */
            getDllinkWebsite();
        } else {
            /* Website */
            if (server_issues) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
            }
        }
        if (dllink == null) {
            dllink = checkDirectLink(downloadLink, directlinkproperty);
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumable, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            }
            br.followConnection();
            if (this.br.containsHTML("class=\"block\"")) {
                /*
                 * <h1>Oops! No premium...</h1> <h2>You can't download more than one file without an premium account. Hang tight.</h2>
                 */
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Wait before more downloads can be started", 3 * 60 * 1000l);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setProperty(directlinkproperty, dllink);
        dl.startDownload();
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                con = br2.openHeadConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
            } catch (final Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return dllink;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    private static Object LOCK = new Object();

    private void login(final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            br.setFollowRedirects(false);
            getAuthToken(account);
            if (this.account_auth_token != null) {
                br.getPage(API_BASE + "accountinfo/" + Encoding.urlEncode(this.account_auth_token));
                if (PluginJSonUtils.getJson(this.br, "email") != null) {
                    /* Saved account_auth_token is still valid! */
                    return;
                }
            }
            br.getPage(API_BASE + "login/" + Encoding.urlEncode(account.getUser()) + "/" + JDHash.getMD5(account.getPass()));
            account_auth_token = PluginJSonUtils.getJson(this.br, "login_token");
            if (br.containsHTML("login_failed") || account_auth_token == null) {
                if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
            }
            saveAuthToken(account);
        }
    }

    private void getAuthToken(final Account acc) {
        this.account_auth_token = acc.getStringProperty("account_auth_token", null);
    }

    private void saveAuthToken(final Account acc) {
        acc.setProperty("account_auth_token", this.account_auth_token);
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        try {
            login(account, true);
        } catch (PluginException e) {
            account.setValid(false);
            throw e;
        }
        ai.setUnlimitedTraffic();
        /* TODO: Set Free + Premium limits once they are available serverside */
        final boolean isPremium = "1".equals(PluginJSonUtils.getJson(this.br, "premium"));
        if (!isPremium) {
            account.setType(AccountType.FREE);
            /* Free accounts can still have captcha */
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            account.setConcurrentUsePossible(false);
            ai.setStatus("Registered (free) user");
        } else {
            final String valid_millisecs = PluginJSonUtils.getJson(this.br, "valid_millisecs");
            ai.setValidUntil(Long.parseLong(valid_millisecs));
            account.setType(AccountType.PREMIUM);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            account.setConcurrentUsePossible(true);
            ai.setStatus("Premium account");
        }
        account.setValid(true);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        login(account, false);
        /* TODO: Set Free + Premium limits once they are available serverside */
        // if (account.getType() == AccountType.FREE) {
        // } else {
        // }
        String dllink = this.checkDirectLink(link, "premium_directlink");
        if (dllink == null) {
            br.getPage(API_BASE + "download/" + Encoding.urlEncode(this.account_auth_token) + "/" + this.folderid + "/" + Encoding.urlEncode(getFilenameProperty(link)));
            dllink = PluginJSonUtils.getJson(this.br, "download_link");

            /* TODO: Try to find a way to get the sha1 hash for unregistered users too! */
            final String sha1 = PluginJSonUtils.getJson(this.br, "sha1");
            if (sha1 != null) {
                link.setSha1Hash(sha1);
            }

            if (dllink == null) {
                handleErrorsAPI();
                logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS);
        if (dl.getConnection().getContentType().contains("html")) {
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            }
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setProperty("premium_directlink", dllink);
        dl.startDownload();
    }

    private void handleErrorsAPI() throws PluginException {
        final String error = PluginJSonUtils.getJson(this.br, "error");
        if (error != null) {
            if (error.equals("file_not_found")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            logger.warning("Unknown API error");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return ACCOUNT_FREE_MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

}