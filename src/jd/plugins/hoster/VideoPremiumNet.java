//jDownloader - Downloadmanager
//Copyright (C) 2012  JD-Team support@jdownloader.org
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

import java.io.File;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.Browser.BrowserException;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.InputField;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.components.SiteType.SiteTemplate;
import jd.plugins.download.DownloadInterface;
import jd.utils.JDUtilities;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "videopremium.tv" }, urls = { "https?://(www\\.)?videopremium\\.(net|tv|me)/(embed\\-)?[a-z0-9]{12}", }, flags = { 2 })
public class VideoPremiumNet extends antiDDoSForHost {

    @Override
    public String[] siteSupportedNames() {
        return new String[] { "videopremium.tv", "videopremium.me" };
        // note: .net is parked
    }

    private String               passCode                     = null;
    private String               correctedBR                  = "";
    private static final String  PASSWORDTEXT                 = "<br><b>Passwor(d|t):</b> <input";
    private final String         COOKIE_HOST                  = "http://videopremium.tv";
    private static final String  CURRENT_DOMAIN               = "videopremium.tv";
    private static final String  NICE_HOSTproperty            = "videopremiumtv";
    private static final String  MAINTENANCE                  = ">This server is in maintenance mode";
    private static final String  MAINTENANCEUSERTEXT          = JDL.L("hoster.xfilesharingprobasic.errors.undermaintenance", "This server is under Maintenance");
    private static final String  ALLWAIT_SHORT                = JDL.L("hoster.xfilesharingprobasic.errors.waitingfordownloads", "Waiting till new downloads can be started");
    private static final String  PREMIUMONLY1                 = JDL.L("hoster.xfilesharingprobasic.errors.premiumonly1", "Max downloadable filesize for free users:");
    private static final String  PREMIUMONLY2                 = JDL.L("hoster.xfilesharingprobasic.errors.premiumonly2", "Only downloadable via premium or registered");
    private static final boolean VIDEOHOSTER                  = true;
    // note: can not be negative -x or 0 .:. [1-*]
    private static AtomicInteger totalMaxSimultanFreeDownload = new AtomicInteger(2);
    // don't touch
    private static AtomicInteger maxFree                      = new AtomicInteger(1);
    private static AtomicInteger maxPrem                      = new AtomicInteger(1);
    private static Object        LOCK                         = new Object();
    private static final boolean SUPPORTS_ALT_AVAILABLECHECK  = true;

    // DEV NOTES
    /**
     * Script notes: Streaming versions of this script sometimes redirect you to their directlinks when accessing this link + the link ID:
     * http://somehoster.in/vidembed-
     */
    // XfileSharingProBasic Version 2.5.6.8-raz
    // mods: this is an RTMP host, removed F1 form- password- and captcha
    // handling
    // non account: 1 * 2
    // free account: 1 * 2
    // premium account: chunk * maxdl
    // protocol: no https
    // captchatype: null
    // other: no redirects, Too many simultan downloads returns 404 instead of 503 - 404 can also be server error (file not found), unclear,
    // FREE accounts = are able to generate HTTP downloadlinks
    // in this case!

    public void correctDownloadLink(final DownloadLink link) {
        link.setUrlDownload(COOKIE_HOST + "/" + new Regex(link.getDownloadURL(), "([a-z0-9]{12})$").getMatch(0));
    }

    @Override
    public String rewriteHost(String host) {
        if ("videopremium.net".equals(getHost())) {
            if (host == null || "videopremium.net".equals(host)) {
                return "videopremium.tv";
            }
        }
        return super.rewriteHost(host);
    }

    @Override
    public String getAGBLink() {
        return COOKIE_HOST + "/tos.html";
    }

    public VideoPremiumNet(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(COOKIE_HOST + "/premium.html");
    }

    // do not add @Override here to keep 0.* compatibility
    public boolean hasAutoCaptcha() {
        return true;
    }

    @Override
    protected boolean useRUA() {
        return true;
    }

    @Override
    protected Browser prepBrowser(final Browser prepBr, final String host) {
        if (!(browserPrepped.containsKey(prepBr) && browserPrepped.get(prepBr) == Boolean.TRUE)) {
            super.prepBrowser(prepBr, host);
            prepBr.setCookie(COOKIE_HOST, "lang", "english");
        }
        return prepBr;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        String[] fileInfo = new String[3];
        final Browser altbr = br.cloneBrowser();
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        // Correct previously added links
        correctDownloadLink(link);
        final String fid = new Regex(link.getDownloadURL(), "([a-z0-9]+)$").getMatch(0);
        /* Workaround not needed anymore */
        // getPage(link.getDownloadURL());
        // getPage("http://" + CURRENT_DOMAIN + "/main?%2F" + fid);
        getPage("http://" + CURRENT_DOMAIN + "/" + fid);
        final String continuelink = br.getRegex(">window\\.location = \\'(http[^<>\"]*?)\\'").getMatch(0);
        if (continuelink != null) {
            getPage(continuelink);
        }
        if (new Regex(correctedBR, "(No such file|>File Not Found<|>The file was removed by|Reason (of|for) deletion:\n|>File has been removed|>Page not found<|>The requested file is not available<)").matches()) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (correctedBR.contains(MAINTENANCE)) {
            link.getLinkStatus().setStatusText(JDL.L("plugins.hoster.xfilesharingprobasic.undermaintenance", MAINTENANCEUSERTEXT));
            return AvailableStatus.TRUE;
        }
        if (correctedBR.contains("No htmlCode read")) {
            return AvailableStatus.UNCHECKABLE;
        }
        // scan the first page
        scanInfo(fileInfo);
        // scan the second page. filesize[1] and md5hash[2] are not mission
        // critical
        if (fileInfo[0] == null) {
            Form download1 = getFormByKey("op", "download1");
            if (download1 != null) {
                download1.remove("method_premium");
                submitForm(download1);
                scanInfo(fileInfo);
            }
        }
        if (fileInfo[0] == null || fileInfo[0].equals("")) {
            if (correctedBR.contains("You have reached the download(\\-| )limit")) {
                logger.warning("Waittime detected, please reconnect to make the linkchecker work!");
                return AvailableStatus.UNCHECKABLE;
            }
            logger.warning("filename equals null, throwing \"plugin defect\"");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (fileInfo[2] != null && !fileInfo[2].equals("")) {
            link.setMD5Hash(fileInfo[2].trim());
        }
        fileInfo[0] = fileInfo[0].replaceAll("(</b>|<b>|\\.html)", "");
        link.setFinalFileName(fileInfo[0].trim());
        if (fileInfo[1] == null && SUPPORTS_ALT_AVAILABLECHECK) {
            /* Do alt availablecheck here but don't check availibility because we already know that the file must be online! */
            logger.info("Filesize not available, trying altAvailablecheck");
            try {
                altbr.postPage(COOKIE_HOST + "/?op=checkfiles", "op=checkfiles&process=Check+URLs&list=" + Encoding.urlEncode(link.getDownloadURL()));
                fileInfo[1] = altbr.getRegex(">" + link.getDownloadURL() + "</td><td style=\"color:green;\">Found</td><td>([^<>\"]*?)</td>").getMatch(0);
            } catch (final Throwable e) {
            }
        }
        if (fileInfo[1] != null && !fileInfo[1].equals("")) {
            link.setDownloadSize(SizeFormatter.getSize(fileInfo[1]));
        }
        return AvailableStatus.TRUE;
    }

    private String[] scanInfo(String[] fileInfo) {
        // standard traits from base page
        if (fileInfo[0] == null) {
            fileInfo[0] = new Regex(correctedBR, "You have requested.*?https?://(www\\.)?" + this.getHost() + "/[A-Za-z0-9]{12}/(.*?)</font>").getMatch(1);
            if (fileInfo[0] == null) {
                fileInfo[0] = new Regex(correctedBR, "fname\"( type=\"hidden\")? value=\"(.*?)\"").getMatch(1);
                if (fileInfo[0] == null) {
                    fileInfo[0] = new Regex(correctedBR, "<h2>Download File(.*?)</h2>").getMatch(0);
                    if (fileInfo[0] == null) {
                        fileInfo[0] = new Regex(correctedBR, "Download File:? ?(<[^>]+> ?)+?([^<>\"\\']+)").getMatch(1);
                        // traits from download1 page below.
                        if (fileInfo[0] == null) {
                            fileInfo[0] = new Regex(correctedBR, "Filename:? ?(<[^>]+> ?)+?([^<>\"\\']+)").getMatch(1);
                            // next two are details from sharing box
                            if (fileInfo[0] == null) {
                                fileInfo[0] = new Regex(correctedBR, "copy\\(this\\);.+>(.+) \\- [\\d\\.]+ (KB|MB|GB)</a></textarea>[\r\n\t ]+</div>").getMatch(0);
                                if (fileInfo[0] == null) {
                                    fileInfo[0] = new Regex(correctedBR, "copy\\(this\\);.+\\](.+) \\- [\\d\\.]+ (KB|MB|GB)\\[/URL\\]").getMatch(0);
                                    if (fileInfo[0] == null) {
                                        fileInfo[0] = new Regex(correctedBR, "name=\"fname\" value=\"([^<>\"]*?)\"").getMatch(0);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return fileInfo;
    }

    @Override
    public void handleFree(DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        /* (or 1 download with max 2 chunks) */
        doFree(downloadLink, true, 1, "freelink");
    }

    public void doFree(final DownloadLink downloadLink, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        if (correctedBR.contains("No htmlCode read")) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error", 2 * 60 * 1000l);
        } else if (oldStyle()) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "This host only works in the JDownloader 2 BETA version.");
        }
        String passCode = null;
        checkErrors(downloadLink, false, passCode);
        String dllink = checkDirectLink(downloadLink, directlinkproperty);
        if (VIDEOHOSTER && !downloadLink.getBooleanProperty("http_failed", false)) {
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(false);
                br2.getPage("http://" + CURRENT_DOMAIN + "/vidembed-" + new Regex(downloadLink.getDownloadURL(), "([a-z0-9]{12})$").getMatch(0));
                dllink = br2.getRedirectLocation();
                if (dllink != null) {
                    // /* thx to raztoki, correct invalid vidembed directlinks */
                    // final String h = new Regex(dllink, "h=([a-z0-9]{50,70})").getMatch(0);
                    // if (h != null) {
                    // dllink = "http://93.115.86.10:182/" + h + "/video.mp4";
                    // }
                }
            } catch (final Throwable e) {
            }
        }
        // Third, continue like normal.
        if (dllink == null) {
            checkErrors(downloadLink, false, passCode);
            Form download1 = getFormByKey("op", "download1");
            if (download1 != null) {
                download1.remove("method_premium");
                submitForm(download1);
                checkErrors(downloadLink, false, passCode);
                dllink = getDllink();
            }
        }
        if (dllink == null) {
            Form dlForm = br.getFormbyProperty("name", "F1");
            if (dlForm != null) {
                logger.info("F1 form is available..");
                if (correctedBR.contains(";background:#ccc;text-align")) {
                    logger.info("Detected captcha method \"plaintext captchas\" for this host");
                    /* Captcha method by ManiacMansion */
                    final String[][] letters = new Regex(br, "<span style=\\'position:absolute;padding\\-left:(\\d+)px;padding\\-top:\\d+px;\\'>(&#\\d+;)</span>").getMatches();
                    if (letters == null || letters.length == 0) {
                        logger.warning("plaintext captchahandling broken!");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    final SortedMap<Integer, String> capMap = new TreeMap<Integer, String>();
                    for (String[] letter : letters) {
                        capMap.put(Integer.parseInt(letter[0]), Encoding.htmlDecode(letter[1]));
                    }
                    final StringBuilder code = new StringBuilder();
                    for (String value : capMap.values()) {
                        code.append(value);
                    }
                    dlForm.put("code", code.toString());
                    logger.info("Put captchacode " + code.toString() + " obtained by captcha metod \"plaintext captchas\" in the form.");
                }
                waitTime(System.currentTimeMillis(), downloadLink);
                submitForm(dlForm);
                dllink = getDllink();
            }
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /* Prefer http downloads */
        if (dllink.startsWith("http")) {
            try {
                dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumable, maxchunks);
            } catch (final SocketTimeoutException e) {
                /* self built http link (workaround to avoid rtmp) does not work --> retry via rtmp */
                downloadLink.setProperty("http_failed", true);
                throw new PluginException(LinkStatus.ERROR_RETRY, "HTTP version download failed, retry RTMP");
            } catch (final BrowserException be) {
                /* self built http link (workaround to avoid rtmp) does not work --> retry via rtmp */
                downloadLink.setProperty("http_failed", true);
                throw new PluginException(LinkStatus.ERROR_RETRY, "HTTP version download failed, retry RTMP");
            }
            if (dl.getConnection().getContentType().contains("html")) {
                if (dl.getConnection().getResponseCode() == 503) {
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Connection limit reached, please contact our support!", 5 * 60 * 1000l);
                }
                logger.warning("The final dllink seems not to be a file!");
                br.followConnection();
                correctBR();
                checkServerErrors();
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else if (dl.getConnection().getLongContentLength() == 0) {
                /* Streams cannot be 0b files */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error - server sends empty file", 30 * 60 * 1000l);
            }
            downloadLink.setProperty(directlinkproperty, dllink);
            dl.startDownload();
        } else {
            try {
                rtmpDownload(downloadLink, dllink);
            } catch (final Throwable e) {
                logger.info(CURRENT_DOMAIN + ": timesfailed_unknown_rtmp_error");
                int timesFailed = downloadLink.getIntegerProperty(NICE_HOSTproperty + "timesfailed_unknown_rtmp_error", 0);
                downloadLink.getLinkStatus().setRetryCount(0);
                if (timesFailed <= 5) {
                    timesFailed++;
                    downloadLink.setProperty(NICE_HOSTproperty + "timesfailed_unknown_rtmp_error", timesFailed);
                    logger.info(CURRENT_DOMAIN + ": timesfailed_unknown_rtmp_error -> Retrying");
                    throw new PluginException(LinkStatus.ERROR_RETRY, "timesfailed_unknown_rtmp_error");
                } else {
                    downloadLink.setProperty(NICE_HOSTproperty + "timesfailed_unknown_rtmp_error", Property.NULL);
                    logger.info(CURRENT_DOMAIN + ": timesfailed_unknown_rtmp_error - disabling current host!");
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown fatal server error", 5 * 60 * 1000l);
                }
            }
        }
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            try {
                final Browser br2 = br.cloneBrowser();
                URLConnectionAdapter con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
                con.disconnect();
            } catch (final Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            }
        }
        return dllink;
    }

    private void waitTime(long timeBefore, final DownloadLink downloadLink) throws PluginException {
        int passedTime = (int) ((System.currentTimeMillis() - timeBefore) / 1000) - 1;
        /** Ticket Time */
        final String ttt = new Regex(correctedBR, "id=\"countdown_str\">[^<>\"]+<span id=\"[^<>\"]+\"( class=\"[^<>\"]+\")?>([\n ]+)?(\\d+)([\n ]+)?</span>").getMatch(2);
        if (ttt != null) {
            int wait = Integer.parseInt(ttt);
            wait -= passedTime;
            logger.info("[Seconds] Waittime on the page: " + ttt);
            logger.info("[Seconds] Passed time: " + passedTime);
            logger.info("[Seconds] Total time to wait: " + wait);
            if (wait > 0) {
                sleep(wait * 1000l, downloadLink);
            }
        }
    }

    private boolean oldStyle() {
        String prev = JDUtilities.getRevision();
        if (prev == null || prev.length() < 3) {
            prev = "0";
        } else {
            prev = prev.replaceAll(",|\\.", "");
        }
        int rev = Integer.parseInt(prev);
        if (rev < 14000) {
            return true;
        }
        return false;
    }

    private void rtmpDownload(DownloadLink downloadLink, String dllink) throws Exception {
        dl = new RTMPDownload(this, downloadLink, dllink);
        setupRTMPConnection(dllink, dl, downloadLink);
        ((RTMPDownload) dl).startDownload();
    }

    private void setupRTMPConnection(String stream, DownloadInterface dl, DownloadLink downloadLink) {
        jd.network.rtmp.url.RtmpUrlConnection rtmp = ((RTMPDownload) dl).getRtmpConnection();
        String url = stream.split("@")[0];
        rtmp.setPlayPath(stream.split("@")[1]);
        rtmp.setUrl(url);
        rtmp.setSwfVfy(stream.split("@")[2]);
        rtmp.setPageUrl(br.getURL());
        rtmp.setConn("S:" + stream.split("@")[1]);
        rtmp.setResume(true);
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return maxFree.get();
    }

    /**
     * Prevents more than one free download from starting at a given time. One step prior to dl.startDownload(), it adds a slot to maxFree
     * which allows the next singleton download to start, or at least try.
     *
     * This is needed because xfileshare(website) only throws errors after a final dllink starts transferring or at a given step within pre
     * download sequence. But this template(XfileSharingProBasic) allows multiple slots(when available) to commence the download sequence,
     * this.setstartintival does not resolve this issue. Which results in x(20) captcha events all at once and only allows one download to
     * start. This prevents wasting peoples time and effort on captcha solving and|or wasting captcha trading credits. Users will experience
     * minimal harm to downloading as slots are freed up soon as current download begins.
     *
     * @param controlFree
     *            (+1|-1)
     */
    public synchronized void controlFree(int num) {
        logger.info("maxFree was = " + maxFree.get());
        maxFree.set(Math.min(Math.max(1, maxFree.addAndGet(num)), totalMaxSimultanFreeDownload.get()));
        logger.info("maxFree now = " + maxFree.get());
    }

    /** Remove HTML code which could break the plugin */
    public void correctBR() throws NumberFormatException, PluginException {
        correctedBR = br.toString();
        ArrayList<String> someStuff = new ArrayList<String>();
        ArrayList<String> regexStuff = new ArrayList<String>();
        regexStuff.add("<\\!(\\-\\-.*?\\-\\-)>");
        regexStuff.add("(display: ?none;\">.*?</div>)");
        regexStuff.add("(visibility:hidden>.*?<)");
        for (String aRegex : regexStuff) {
            String lolz[] = br.getRegex(aRegex).getColumn(0);
            if (lolz != null) {
                for (String dingdang : lolz) {
                    someStuff.add(dingdang);
                }
            }
        }
        for (String fun : someStuff) {
            correctedBR = correctedBR.replace(fun, "");
        }
    }

    public String getDllink() {
        String dllink = br.getRedirectLocation();
        if (dllink == null) {
            String cryptedScripts[] = new Regex(correctedBR, "p\\}\\((.*?)\\.split\\('\\|'\\)").getColumn(0);
            if (cryptedScripts != null && cryptedScripts.length != 0) {
                for (String crypted : cryptedScripts) {
                    dllink = decodeDownloadLink(crypted);
                    if (dllink != null) {
                        break;
                    }
                }
            }
            if (dllink == null) {
                String in = new Regex(correctedBR, "flashvars = \\{([^\\}]+)\\}").getMatch(0);
                String out[] = new Regex(in == null ? "" : in, "\"?(file|p2pkey)\"?:\"?(.*?)\"?,").getColumn(1);
                dllink = new Regex(correctedBR, "swfobject\\.embedSWF\\(\"([^\"]+)\",").getMatch(0);
                if (out.length != 2 || dllink == null) {
                    return null;
                }
                if (dllink.startsWith("/")) {
                    dllink = "http://" + CURRENT_DOMAIN + dllink;
                }
                dllink = out[0] + "@" + out[1] + "@" + dllink;
            }
        }
        return dllink;
    }

    @Override
    protected void getPage(String page) throws Exception {
        super.getPage(page);
        correctBR();
    }

    @Override
    protected void submitForm(Form form) throws Exception {
        super.submitForm(form);
        correctBR();
    }

    public void checkErrors(DownloadLink theLink, boolean checkAll, String passCode) throws NumberFormatException, PluginException {
        if (checkAll) {
            if (new Regex(correctedBR, PASSWORDTEXT).matches() || correctedBR.contains("Wrong password")) {
                logger.warning("Wrong password, the entered password \"" + passCode + "\" is wrong, retrying...");
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
            }
            if (correctedBR.contains("Wrong captcha")) {
                logger.warning("Wrong captcha or wrong password!");
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            if (correctedBR.contains("\">Skipped countdown<")) {
                throw new PluginException(LinkStatus.ERROR_FATAL, "Fatal countdown error (countdown skipped)");
            }
        }
        /** Wait time reconnect handling */
        if (new Regex(correctedBR, "(You have reached the download(\\-| )limit|You have to wait)").matches()) {
            // adjust this regex to catch the wait time string for COOKIE_HOST
            String WAIT = new Regex(correctedBR, "((You have reached the download(\\-| )limit|You have to wait)[^<>]+)").getMatch(0);
            String tmphrs = new Regex(WAIT, "\\s+(\\d+)\\s+hours?").getMatch(0);
            if (tmphrs == null) {
                tmphrs = new Regex(correctedBR, "You have to wait.*?\\s+(\\d+)\\s+hours?").getMatch(0);
            }
            String tmpmin = new Regex(WAIT, "\\s+(\\d+)\\s+minutes?").getMatch(0);
            if (tmpmin == null) {
                tmpmin = new Regex(correctedBR, "You have to wait.*?\\s+(\\d+)\\s+minutes?").getMatch(0);
            }
            String tmpsec = new Regex(WAIT, "\\s+(\\d+)\\s+seconds?").getMatch(0);
            String tmpdays = new Regex(WAIT, "\\s+(\\d+)\\s+days?").getMatch(0);
            if (tmphrs == null && tmpmin == null && tmpsec == null && tmpdays == null) {
                logger.info("Waittime regexes seem to be broken");
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 60 * 60 * 1000l);
            } else {
                int minutes = 0, seconds = 0, hours = 0, days = 0;
                if (tmphrs != null) {
                    hours = Integer.parseInt(tmphrs);
                }
                if (tmpmin != null) {
                    minutes = Integer.parseInt(tmpmin);
                }
                if (tmpsec != null) {
                    seconds = Integer.parseInt(tmpsec);
                }
                if (tmpdays != null) {
                    days = Integer.parseInt(tmpdays);
                }
                int waittime = ((days * 24 * 3600) + (3600 * hours) + (60 * minutes) + seconds + 1) * 1000;
                logger.info("Detected waittime #2, waiting " + waittime + "milliseconds");
                /** Not enough wait time to reconnect->Wait and try again */
                if (waittime < 180000) {
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.xfilesharingprobasic.allwait", ALLWAIT_SHORT), waittime);
                }
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, waittime);
            }
        }
        if (correctedBR.contains("You're using all download slots for IP")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l);
        }
        if (correctedBR.contains("Error happened when generating Download Link")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error!", 10 * 60 * 1000l);
        }
        /** Error handling for only-premium links */
        if (new Regex(correctedBR, "( can download files up to |Upgrade your account to download bigger files|>Upgrade your account to download larger files|>The file you requested reached max downloads limit for Free Users|Please Buy Premium To download this file<|This file reached max downloads limit)").matches()) {
            String filesizelimit = new Regex(correctedBR, "You can download files up to(.*?)only").getMatch(0);
            if (filesizelimit != null) {
                filesizelimit = filesizelimit.trim();
                logger.warning("As free user you can download files up to " + filesizelimit + " only");
                try {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } catch (final Throwable e) {
                    if (e instanceof PluginException) {
                        throw (PluginException) e;
                    }
                }
                throw new PluginException(LinkStatus.ERROR_FATAL, PREMIUMONLY1 + " " + filesizelimit);
            } else {
                logger.warning("Only downloadable via premium");
                try {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } catch (final Throwable e) {
                    if (e instanceof PluginException) {
                        throw (PluginException) e;
                    }
                }
                throw new PluginException(LinkStatus.ERROR_FATAL, PREMIUMONLY2);
            }
        }
        if (correctedBR.contains(MAINTENANCE)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, MAINTENANCEUSERTEXT, 2 * 60 * 60 * 1000l);
        }
    }

    public void checkServerErrors() throws NumberFormatException, PluginException {
        if (new Regex(correctedBR, Pattern.compile("No file", Pattern.CASE_INSENSITIVE)).matches()) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "Server error");
        }
        if (new Regex(correctedBR, "(File Not Found|<h1>404 Not Found</h1>)").matches() || dl.getConnection().getResponseCode() == 404) {
            logger.warning("Server says link offline, please recheck that!");
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Either too many simultan downloads or server error 404", 5 * 60 * 1000l);
        }
    }

    private String decodeDownloadLink(String s) {
        String decoded = null;

        try {
            Regex params = new Regex(s, "\\'(.*?[^\\\\])\\',(\\d+),(\\d+),\\'(.*?)\\'");

            String p = params.getMatch(0).replaceAll("\\\\", "");
            int a = Integer.parseInt(params.getMatch(1));
            int c = Integer.parseInt(params.getMatch(2));
            String[] k = params.getMatch(3).split("\\|");

            while (c != 0) {
                c--;
                if (k[c].length() != 0) {
                    p = p.replaceAll("\\b" + Integer.toString(c, a) + "\\b", k[c]);
                }
            }

            decoded = p;
        } catch (Exception e) {
        }

        String finallink = null;
        if (decoded != null) {
            String rtmpStuff[] = new Regex(decoded, "flashvars=\\{\"comment\":\"[^\"]+\",\"st\":\"[^\"]+\",\"file\":\"([^\"]+)\",p2pkey:\"([^\"]+)\",.*?swfobject\\.embedSWF\\(\"([^\"]+)\",").getRow(0);
            if (rtmpStuff != null && rtmpStuff.length == 3) {
                String player = rtmpStuff[2];
                if (!player.startsWith("http://")) {
                    player = COOKIE_HOST + player;
                }
                finallink = rtmpStuff[0] + "@" + rtmpStuff[1] + "@" + player;
            }
        }
        return finallink;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
        link.setProperty("REDIRECT", Property.NULL);
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        /* reset maxPrem workaround on every fetchaccount info */
        maxPrem.set(1);
        try {
            login(account, true);
        } catch (PluginException e) {
            account.setValid(false);
            return ai;
        }
        account.setValid(true);
        ai.setUnlimitedTraffic();
        if (account.getBooleanProperty("free", false)) {
            maxPrem.set(2);
            try {
                account.setType(AccountType.FREE);
                account.setMaxSimultanDownloads(maxPrem.get());
                account.setConcurrentUsePossible(false);
            } catch (final Throwable e) {
                /* not available in old Stable 0.9.581 */
            }
            ai.setStatus("Registered (free) user");
        } else {
            final String expire = new Regex(correctedBR, "(\\d{1,2} (January|February|March|April|May|June|July|August|September|October|November|December) \\d{4})").getMatch(0);
            if (expire == null) {
                ai.setExpired(true);
                account.setValid(false);
                return ai;
            } else {
                ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "dd MMMM yyyy", Locale.ENGLISH));
                try {
                    maxPrem.set(20);
                    account.setMaxSimultanDownloads(maxPrem.get());
                    account.setConcurrentUsePossible(true);
                } catch (final Throwable e) {
                    // not available in old Stable 0.9.581
                }
            }
            ai.setStatus("Premium User");
        }
        return ai;
    }

    @SuppressWarnings("unchecked")
    private void login(final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                /** Load cookies */
                br.setCookiesExclusive(true);
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof HashMap<?, ?> && !force) {
                    final HashMap<String, String> cookies = (HashMap<String, String>) ret;
                    if (account.isValid()) {
                        for (final Map.Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            this.br.setCookie(COOKIE_HOST, key, value);
                        }
                        return;
                    }
                }
                br.setFollowRedirects(true);
                getPage(COOKIE_HOST + "/login.html");
                final Form loginform = br.getFormbyProperty("name", "FL");
                if (loginform == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                // Handle stupid login captcha
                if (br.containsHTML("solvemedia\\.com/papi/")) {
                    final DownloadLink dummyLink = new DownloadLink(this, "Account", "videopremium.tv", "http://videopremium.tv", true);

                    final org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia sm = new org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia(br);
                    final File cf = sm.downloadCaptcha(getLocalCaptchaFile());
                    final String code = getCaptchaCode(cf, dummyLink);
                    loginform.put("adcopy_response", code);
                    loginform.put("adcopy_challenge", sm.getChallenge(code));
                }
                loginform.remove("redirect");
                loginform.remove(null);
                loginform.remove(null);
                loginform.put("login", Encoding.urlEncode(account.getUser()));
                loginform.put("password", Encoding.urlEncode(account.getPass()));
                loginform.put("redirect", "http://" + CURRENT_DOMAIN + "/");
                submitForm(loginform);
                if (br.getCookie(COOKIE_HOST, "login") == null || br.getCookie(COOKIE_HOST, "xfss") == null) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen? Versuche folgendes:\r\n1. Falls dein Passwort Sonderzeichen enthält, ändere es (entferne diese) und versuche es erneut!\r\n2. Gib deine Zugangsdaten per Hand (ohne kopieren/einfügen) ein.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nYou're sure that the username and password you entered are correct? Some hints:\r\n1. If your password contains special characters, change it (remove them) and try again!\r\n2. Type in your username/password by hand without copy & paste.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                if (!br.getURL().contains("/?op=my_account")) {
                    getPage("/?op=my_account");
                }
                if (!new Regex(correctedBR, ">Renew premium</strong>").matches()) {
                    account.setProperty("free", true);
                } else {
                    account.setProperty("free", false);
                }
                /** Save cookies */
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = this.br.getCookies(COOKIE_HOST);
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    @Override
    public void handlePremium(final DownloadLink downloadLink, final Account account) throws Exception {
        passCode = downloadLink.getStringProperty("pass");
        requestFileInformation(downloadLink);
        if (oldStyle()) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "This host only works in the JDownloader 2 BETA version.");
        }
        login(account, false);
        br.setFollowRedirects(false);
        getPage(downloadLink.getDownloadURL());
        if (account.getBooleanProperty("free", false)) {
            doFree(downloadLink, true, 1, "directlink_account_free");
        } else {
            String dllink = getDllink();
            if (dllink == null) {
                final Form dlform = br.getFormbyProperty("name", "F1");
                if (dlform != null && new Regex(correctedBR, PASSWORDTEXT).matches()) {
                    passCode = handlePassword(dlform, downloadLink);
                }
                checkErrors(downloadLink, true, passCode);
                if (dlform == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                submitForm(dlform);
                checkErrors(downloadLink, true, passCode);
                dllink = getDllink();
            }
            if (dllink == null) {
                logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            if (dllink.startsWith("rtmp")) {
                rtmpDownload(downloadLink, dllink);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Code-part not yet done...");
            }
        }
    }

    private String handlePassword(final Form pwform, final DownloadLink thelink) throws PluginException {
        if (passCode == null) {
            passCode = Plugin.getUserInput("Password?", thelink);
        }
        if (passCode == null || passCode.equals("")) {
            logger.info("User has entered blank password, exiting handlePassword");
            passCode = null;
            thelink.setProperty("pass", Property.NULL);
            return null;
        }
        if (pwform == null) {
            // so we know handlePassword triggered without any form
            logger.info("Password Form == null");
        } else {
            logger.info("Put password \"" + passCode + "\" entered by user in the DLForm.");
            pwform.put("password", Encoding.urlEncode(passCode));
        }
        thelink.setProperty("pass", passCode);
        return passCode;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        /* workaround for free/premium issue on stable 09581 */
        return maxPrem.get();
    }

    // TODO: remove this when v2 becomes stable. use br.getFormbyKey(String key,
    // String value)
    /**
     * Returns the first form that has a 'key' that equals 'value'.
     *
     * @param key
     * @param value
     * @return
     */
    private Form getFormByKey(final String key, final String value) {
        Form[] workaround = br.getForms();
        if (workaround != null) {
            for (Form f : workaround) {
                for (InputField field : f.getInputFields()) {
                    if (key != null && key.equals(field.getKey())) {
                        if (value == null && field.getValue() == null) {
                            return f;
                        }
                        if (value != null && value.equals(field.getValue())) {
                            return f;
                        }
                    }
                }
            }
        }
        return null;
    }

    /* NO OVERRIDE!! We need to stay 0.9*compatible */
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        if (acc == null) {
            /* no account, yes we can expect captcha */
            return true;
        }
        if (Boolean.TRUE.equals(acc.getBooleanProperty("free"))) {
            /* free accounts also have captchas */
            return true;
        }
        return false;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.SibSoft_XFileShare;
    }

}