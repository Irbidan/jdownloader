package org.jdownloader.api.cnl2;

import java.awt.Dialog.ModalityType;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

import javax.swing.Icon;

import jd.controlling.linkcollector.LinkCollectingJob;
import jd.controlling.linkcollector.LinkCollector;
import jd.controlling.linkcollector.LinkOrigin;
import jd.controlling.linkcollector.LinkOriginDetails;
import jd.controlling.linkcrawler.CrawledLink;
import jd.controlling.linkcrawler.CrawledLinkModifier;
import jd.controlling.linkcrawler.LinkCrawler;
import jd.controlling.linkcrawler.PackageInfo;
import jd.controlling.linkcrawler.UnknownCrawledLinkHandler;
import jd.http.Browser;
import jd.plugins.DownloadLink;
import jd.plugins.Plugin;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;
import net.sf.image4j.codec.ico.ICOEncoder;

import org.appwork.net.protocol.http.HTTPConstants;
import org.appwork.remoteapi.RemoteAPI;
import org.appwork.remoteapi.RemoteAPIRequest;
import org.appwork.remoteapi.RemoteAPIResponse;
import org.appwork.remoteapi.exceptions.InternalApiException;
import org.appwork.storage.JSonStorage;
import org.appwork.storage.config.JsonConfig;
import org.appwork.storage.simplejson.JSonObject;
import org.appwork.storage.simplejson.JSonValue;
import org.appwork.uio.ConfirmDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.IO;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.HexFormatter;
import org.appwork.utils.images.IconIO;
import org.appwork.utils.logging.Log;
import org.appwork.utils.net.HTTPHeader;
import org.appwork.utils.net.PublicSuffixList;
import org.appwork.utils.net.httpserver.requests.HttpRequestInterface;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.appwork.utils.swing.dialog.DialogNoAnswerException;
import org.jdownloader.api.RemoteAPIConfig;
import org.jdownloader.api.cnl2.translate.ExternInterfaceTranslation;
import org.jdownloader.api.myjdownloader.MyJDownloaderSettings;
import org.jdownloader.images.NewTheme;
import org.jdownloader.plugins.controller.host.HostPluginController;
import org.jdownloader.plugins.controller.host.LazyHostPlugin;

public class ExternInterfaceImpl implements Cnl2APIBasics, Cnl2APIFlash {

    private final static String jdpath = JDUtilities.getJDHomeDirectoryFromEnvironment().getAbsolutePath() + File.separator + "JDownloader.jar";

    public void crossdomainxml(RemoteAPIResponse response) throws InternalApiException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?>\r\n");
        sb.append("<!DOCTYPE cross-domain-policy SYSTEM \"http://www.macromedia.com/xml/dtds/cross-domain-policy.dtd\">\r\n");
        sb.append("<cross-domain-policy>\r\n");
        sb.append("<allow-access-from domain=\"*\" />\r\n");
        sb.append("</cross-domain-policy>\r\n");
        writeString(response, null, sb.toString(), false);
    }

    /**
     * writes given String to response and sets content-type to text/html
     *
     * @param response
     * @param string
     * @throws InternalApiException
     */
    private void writeString(RemoteAPIResponse response, RemoteAPIRequest request, String string, boolean wrapCallback) throws InternalApiException {
        OutputStream out = null;
        try {
            response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_TYPE, "text/html", false));
            out = RemoteAPI.getOutputStream(response, request, false, true);
            if (wrapCallback && request.getJqueryCallback() != null) {
                if (string == null) {
                    string = "";
                }
                string = "{\"content\": \"" + string.trim() + "\"}";
            }
            out.write(string.getBytes("UTF-8"));
        } catch (Throwable e) {
            throw new InternalApiException(e);
        } finally {
            try {
                out.close();
            } catch (final Throwable e) {
            }
        }
    }

    public void jdcheckjs(RemoteAPIResponse response) throws InternalApiException {
        StringBuilder sb = new StringBuilder();
        sb.append("jdownloader=true;\r\n");
        sb.append("var version='" + JDUtilities.getRevision() + "';\r\n");
        writeString(response, null, sb.toString(), false);
    }

    public void jdcheckjson(RemoteAPIResponse response) throws InternalApiException {
        MyJDownloaderSettings set = JsonConfig.create(MyJDownloaderSettings.class);
        JSonObject obj = new JSonObject();
        obj.put("version", new JSonValue(JDUtilities.getRevision()));
        obj.put("deviceId", new JSonValue(set.getUniqueDeviceIDV2()));
        obj.put("name", new JSonValue(set.getDeviceName()));
        writeString(response, null, obj.toString(), false);
    }

    public void addcrypted2(RemoteAPIResponse response, RemoteAPIRequest request) throws InternalApiException {
        try {
            askPermission(request);
            String crypted = request.getParameterbyKey("crypted");
            String jk = request.getParameterbyKey("jk");
            String k = request.getParameterbyKey("k");
            String urls = decrypt(crypted, jk, k);
            clickAndLoad2Add(new LinkOriginDetails(LinkOrigin.CNL, request.getRequestHeaders().getValue("user-agent")), urls, request);
            /*
             * we need the \r\n else the website will not handle response correctly
             */
            writeString(response, request, "success\r\n", true);
        } catch (Throwable e) {
            e.printStackTrace();
            writeString(response, request, "failed " + e.getMessage() + "\r\n", true);
        }
    }

    // For My JD API
    public void addcrypted2Remote(String crypted, String jk, String source) {
        try {
            String urls = decrypt(crypted, jk, null);
            LinkCollectingJob job = new LinkCollectingJob(new LinkOriginDetails(LinkOrigin.CNL, null), urls);
            job.setCustomSourceUrl(source);
            LinkCollector.getInstance().addCrawlerJob(job);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private String getLongerString(String a, String b) {
        if (a != null && b != null) {
            if (a.length() > b.length()) {
                return a;
            }
            return b;
        } else if (a != null && b == null) {
            return a;
        } else if (b != null && a == null) {
            return b;
        }
        return null;
    }

    private void clickAndLoad2Add(LinkOriginDetails origin, String urls, RemoteAPIRequest request) throws IOException {
        final String finalPasswords = request.getParameterbyKey("passwords");
        String source = request.getParameterbyKey("source");
        final String referer = request.getRequestHeaders().getValue(HTTPConstants.HEADER_REQUEST_REFERER);
        String linkComment = request.getParameterbyKey("comment");
        final LinkCollectingJob job = new LinkCollectingJob(origin, urls);
        final String finalDestination = request.getParameterbyKey("dir");
        String packageName = request.getParameterbyKey("package");
        String packageComment = null;
        if (source != null && !(StringUtils.startsWithCaseInsensitive(source, "http://") || StringUtils.startsWithCaseInsensitive(source, "https://"))) {
            final PublicSuffixList psl = PublicSuffixList.getInstance();
            if (psl == null || psl.getDomain(source.toLowerCase(Locale.ENGLISH)) == null) {
                packageComment = source;
            }
            source = null;
        }
        if (source != null) {
            job.setCustomSourceUrl(getLongerString(source, referer));
        } else {
            job.setCustomSourceUrl(referer);
        }
        final String finalPackageName = packageName;
        final String finalComment = linkComment;
        final String finalPackageComment;
        if (linkComment != null) {
            finalPackageComment = linkComment;
        } else {
            finalPackageComment = packageComment;
        }
        final CrawledLinkModifier modifier = new CrawledLinkModifier() {
            private HashSet<String> pws = null;
            {
                if (StringUtils.isNotEmpty(finalPasswords)) {
                    pws = new HashSet<String>();
                    pws.add(finalPasswords);
                }
            }

            @Override
            public void modifyCrawledLink(CrawledLink link) {
                if (StringUtils.isNotEmpty(finalDestination)) {
                    PackageInfo packageInfo = link.getDesiredPackageInfo();
                    if (packageInfo == null) {
                        packageInfo = new PackageInfo();
                    }
                    packageInfo.setDestinationFolder(finalDestination);
                    packageInfo.setIgnoreVarious(true);
                    packageInfo.setUniqueId(null);
                    link.setDesiredPackageInfo(packageInfo);
                }
                if (StringUtils.isNotEmpty(finalPackageName)) {
                    PackageInfo packageInfo = link.getDesiredPackageInfo();
                    if (packageInfo == null) {
                        packageInfo = new PackageInfo();
                    }
                    packageInfo.setName(finalPackageName);
                    packageInfo.setIgnoreVarious(true);
                    packageInfo.setUniqueId(null);
                    link.setDesiredPackageInfo(packageInfo);
                }
                if (StringUtils.isNotEmpty(finalPackageComment)) {
                    PackageInfo packageInfo = link.getDesiredPackageInfo();
                    if (packageInfo == null) {
                        packageInfo = new PackageInfo();
                    }
                    packageInfo.setComment(finalPackageComment);
                    packageInfo.setIgnoreVarious(true);
                    packageInfo.setUniqueId(null);
                    link.setDesiredPackageInfo(packageInfo);
                }
                final DownloadLink dlLink = link.getDownloadLink();
                if (dlLink != null) {
                    if (StringUtils.isNotEmpty(finalComment)) {
                        dlLink.setComment(finalComment);
                    }
                }
                if (pws != null && pws.size() > 0) {
                    link.getArchiveInfo().getExtractionPasswords().addAll(pws);
                }
            }
        };
        job.setCrawledLinkModifierPrePackagizer(modifier);
        if (StringUtils.isNotEmpty(finalPackageName) || StringUtils.isNotEmpty(finalDestination)) {
            job.setCrawledLinkModifierPostPackagizer(modifier);
        }
        LinkCollector.getInstance().addCrawlerJob(job);
    }

    public String decrypt(String crypted, final String jk, String k) throws UnsupportedEncodingException {
        final HashMap<String, String> infos = new HashMap<String, String>();
        infos.put("crypted", crypted);
        if (jk != null) {
            infos.put("jk", jk);
        }
        if (k != null) {
            infos.put("k", k);
        }
        final String json = JSonStorage.toString(infos);
        return "http://dummycnl.jdownloader.org/" + HexFormatter.byteArrayToHex(json.getBytes("UTF-8"));
    }

    public void add(RemoteAPIResponse response, RemoteAPIRequest request) throws InternalApiException {
        try {
            askPermission(request);
            String urls = request.getParameterbyKey("urls");
            clickAndLoad2Add(new LinkOriginDetails(LinkOrigin.CNL, request.getRequestHeaders().getValue("user-agent")), urls, request);
            writeString(response, request, "success\r\n", true);
        } catch (Throwable e) {
            writeString(response, request, "failed " + e.getMessage() + "\r\n", true);
        }
    }

    // For My JD API
    @Override
    public void add(RemoteAPIRequest request, RemoteAPIResponse response, String param1, String param2, String param3) throws InternalApiException {
        try {
            askPermission(request);
            String source = null;
            try {
                source = request.getParameterbyKey("source");
            } catch (IOException e) {
            }
            if (source == null) {
                source = param1;
            }
            String urls = null;
            try {
                urls = request.getParameterbyKey("urls");
            } catch (IOException e) {
            }
            if (urls == null) {
                urls = param2;
            }
            String passwords = null;
            try {
                passwords = request.getParameterbyKey("passwords");
            } catch (IOException e) {
            }
            if (passwords == null) {
                passwords = param3;
            }
            final String finalPasswords = request.getParameterbyKey("passwords");
            final String finalComment = request.getParameterbyKey("comment");
            LinkCollectingJob job = new LinkCollectingJob(new LinkOriginDetails(LinkOrigin.CNL, request.getRequestHeaders().getValue("user-agent")), urls);
            final String finalDestination = request.getParameterbyKey("dir");
            job.setCustomSourceUrl(source);
            final String finalPackageName = request.getParameterbyKey("package");
            final CrawledLinkModifier modifier = new CrawledLinkModifier() {
                private HashSet<String> pws = null;
                {
                    if (StringUtils.isNotEmpty(finalPasswords)) {
                        pws = new HashSet<String>();
                        pws.add(finalPasswords);
                    }
                }

                @Override
                public void modifyCrawledLink(CrawledLink link) {
                    if (StringUtils.isNotEmpty(finalDestination)) {
                        PackageInfo packageInfo = link.getDesiredPackageInfo();
                        if (packageInfo == null) {
                            packageInfo = new PackageInfo();
                        }
                        packageInfo.setDestinationFolder(finalDestination);
                        packageInfo.setIgnoreVarious(true);
                        packageInfo.setUniqueId(null);
                        link.setDesiredPackageInfo(packageInfo);
                    }
                    if (StringUtils.isNotEmpty(finalPackageName)) {
                        PackageInfo packageInfo = link.getDesiredPackageInfo();
                        if (packageInfo == null) {
                            packageInfo = new PackageInfo();
                        }
                        packageInfo.setName(finalPackageName);
                        packageInfo.setIgnoreVarious(true);
                        packageInfo.setUniqueId(null);
                        link.setDesiredPackageInfo(packageInfo);
                    }
                    DownloadLink dlLink = link.getDownloadLink();
                    if (dlLink != null) {
                        if (StringUtils.isNotEmpty(finalComment)) {
                            dlLink.setComment(finalComment);
                        }
                    }
                    if (pws != null && pws.size() > 0) {
                        link.getArchiveInfo().getExtractionPasswords().addAll(pws);
                    }
                }
            };
            job.setCrawledLinkModifierPrePackagizer(modifier);
            if (StringUtils.isNotEmpty(finalPackageName) || StringUtils.isNotEmpty(finalDestination)) {
                job.setCrawledLinkModifierPostPackagizer(modifier);
            }
            LinkCollector.getInstance().addCrawlerJob(job);
        } catch (Throwable e) {
        }
    }

    public void addcrypted(RemoteAPIResponse response, RemoteAPIRequest request) throws InternalApiException {
        try {
            askPermission(request);
            final String dlcContent = request.getParameterbyKey("crypted");
            if (dlcContent == null) {
                throw new IllegalArgumentException("no DLC Content available");
            }
            final String dlc = dlcContent.trim().replace(" ", "+");
            final File tmp = Application.getTempResource("jd_" + System.currentTimeMillis() + ".dlc");
            IO.writeToFile(tmp, dlc.getBytes("UTF-8"));
            final String url = tmp.toURI().toString();
            clickAndLoad2Add(new LinkOriginDetails(LinkOrigin.CNL, request.getRequestHeaders().getValue("user-agent")), url, request);
            writeString(response, request, "success\r\n", true);
        } catch (Throwable e) {
            writeString(response, request, "failed " + e.getMessage() + "\r\n", true);
        }
    }

    private synchronized void askPermission(HttpRequestInterface request) throws IOException, DialogNoAnswerException {
        HTTPHeader jdrandomNumber = request.getRequestHeaders().get("jd.randomnumber");
        if (jdrandomNumber != null && jdrandomNumber.getValue() != null && jdrandomNumber.getValue().equalsIgnoreCase(System.getProperty("jd.randomNumber"))) {
            /*
             * request knows secret jd.randomnumber, it is okay to handle this request
             */
            return;
        }
        HTTPHeader referer = request.getRequestHeaders().get(HTTPConstants.HEADER_REQUEST_REFERER);
        String check = null;
        if (referer != null && (check = referer.getValue()) != null) {
            if (check.equalsIgnoreCase("http://localhost:9666/flashgot") || check.equalsIgnoreCase("http://127.0.0.1:9666/flashgot")) {
                /*
                 * security check for flashgot referer, skip asking if we find valid flashgot referer
                 */
                return;
            }
        }
        String app = "unknown application";
        HTTPHeader agent = request.getRequestHeaders().get(HTTPConstants.HEADER_REQUEST_USER_AGENT);
        if (agent != null && agent.getValue() != null) {
            /* try to parse application name from user agent header */
            app = agent.getValue().replaceAll("\\(.*\\)", "");
        }
        String url = null;
        if (referer != null) {
            /* lets use the referer as source of the request */
            url = referer.getValue();
        }
        if (url == null) {
            /* no referer available, maybe a source variable is? */
            url = request.getParameterbyKey("source");
        }
        if (url != null) {
            url = Browser.getHost(url);
        }
        ArrayList<String> allowed = JsonConfig.create(RemoteAPIConfig.class).getExternInterfaceAuth();
        if (allowed != null && url != null && allowed.contains(url)) {
            /* the url is already allowed to add links */
            return;
        }
        String from = url != null ? url : app;
        try {
            ConfirmDialog d = new ConfirmDialog(0, ExternInterfaceTranslation._.jd_plugins_optional_interfaces_jdflashgot_security_title(from), ExternInterfaceTranslation._.jd_plugins_optional_interfaces_jdflashgot_security_message(), null, ExternInterfaceTranslation._.jd_plugins_optional_interfaces_jdflashgot_security_btn_allow(), ExternInterfaceTranslation._.jd_plugins_optional_interfaces_jdflashgot_security_btn_deny()) {
                @Override
                public ModalityType getModalityType() {
                    return ModalityType.MODELESS;
                }
            };
            UIOManager.I().show(ConfirmDialogInterface.class, d).throwCloseExceptions();
        } catch (DialogNoAnswerException e) {
            throw e;
        }
        if (url != null) {
            /* we can only save permission if an url is available */
            if (allowed == null) {
                allowed = new ArrayList<String>();
            }
            allowed.add(url);
            JsonConfig.create(RemoteAPIConfig.class).setExternInterfaceAuth(allowed);
        }

    }

    public void alive(RemoteAPIResponse response, RemoteAPIRequest request) throws InternalApiException {
        writeString(response, request, "JDownloader\r\n", true);
    }

    public void favicon(RemoteAPIResponse response) throws InternalApiException {
        OutputStream out = null;
        try {
            response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_TYPE, "image/x-icon", false));
            out = RemoteAPI.getOutputStream(response, null, false, false);
            Icon logo = NewTheme.I().getIcon("logo/jd_logo_128_128", 32);
            ICOEncoder.write(IconIO.toBufferedImage(logo), out);
        } catch (Throwable e) {
            throw new InternalApiException(e);
        } finally {
            try {
                out.close();
            } catch (final Throwable e) {
            }
        }
    }

    public void flashgot(RemoteAPIResponse response, RemoteAPIRequest request) throws InternalApiException {
        try {
            askPermission(request);
            StringBuilder sb = new StringBuilder();
            sb.append(jdpath + "\r\n");
            sb.append("java -Xmx512m -jar " + jdpath + "\r\n");
            final String urls[] = Regex.getLines(request.getParameterbyKey("urls"));
            if (urls != null && urls.length > 0) {
                final String desc[] = Regex.getLines(request.getParameterbyKey("descriptions"));
                final String fnames[] = Regex.getLines(request.getParameterbyKey("fnames"));
                final String cookies = request.getParameterbyKey("cookies");
                final String post = request.getParameterbyKey("postData");
                final String referer = request.getParameterbyKey("referer");
                final String downloadPasswords[] = Regex.getLines(request.getParameterbyKey("dpass"));
                final String archivePasswords[] = Regex.getLines(request.getParameterbyKey("apass"));
                final boolean finalAutostart = "1".equals(request.getParameterbyKey("autostart"));
                /*
                 * create LinkCollectingJob to forward general Information like directory, autostart...
                 */
                LinkCollectingJob job = new LinkCollectingJob(new LinkOriginDetails(LinkOrigin.FLASHGOT, request.getRequestHeaders().getValue("user-agent")), null);
                final String finalPackageName = request.getParameterbyKey("package");
                String dir = request.getParameterbyKey("dir");
                if (dir != null && dir.matches("^[a-zA-Z]{1}:$")) {
                    /* flashgot seems unable to set x:/ <-> only x: is possible */
                    dir = dir + "/";
                }
                final String finalDestination = dir;

                final CrawledLinkModifier modifier = new CrawledLinkModifier() {
                    private HashSet<String> pws = null;
                    {
                        if (archivePasswords != null) {
                            pws = new HashSet<String>();
                            for (String p : archivePasswords) {
                                if (StringUtils.isNotEmpty(p)) {
                                    pws.add(p);
                                }
                            }
                            if (pws.size() == 0) {
                                pws = null;
                            }
                        }
                    }

                    @Override
                    public void modifyCrawledLink(CrawledLink link) {
                        if (StringUtils.isNotEmpty(finalPackageName)) {
                            PackageInfo packageInfo = link.getDesiredPackageInfo();
                            if (packageInfo == null) {
                                packageInfo = new PackageInfo();
                            }
                            packageInfo.setName(finalPackageName);
                            packageInfo.setUniqueId(null);
                            packageInfo.setIgnoreVarious(true);
                            link.setDesiredPackageInfo(packageInfo);
                        }
                        if (StringUtils.isNotEmpty(finalDestination)) {
                            PackageInfo packageInfo = link.getDesiredPackageInfo();
                            if (packageInfo == null) {
                                packageInfo = new PackageInfo();
                            }
                            packageInfo.setDestinationFolder(finalDestination);
                            packageInfo.setIgnoreVarious(true);
                            packageInfo.setUniqueId(null);
                            link.setDesiredPackageInfo(packageInfo);
                        }
                        if (pws != null && pws.size() > 0) {
                            link.getArchiveInfo().getExtractionPasswords().addAll(pws);
                        }
                        if (finalAutostart) {
                            link.setAutoConfirmEnabled(true);
                            link.setAutoStartEnabled(true);
                        }
                    }
                };
                job.setCrawledLinkModifierPrePackagizer(modifier);
                if (StringUtils.isNotEmpty(finalPackageName) || StringUtils.isNotEmpty(finalDestination)) {
                    job.setCrawledLinkModifierPostPackagizer(modifier);
                }

                LazyHostPlugin lazyp = HostPluginController.getInstance().get("DirectHTTP");
                final PluginForHost defaultplg = lazyp.getPrototype(null);

                java.util.List<CrawledLink> links = new ArrayList<CrawledLink>();
                for (int index = 0; index <= urls.length - 1; index++) {
                    CrawledLink link = new CrawledLink(urls[index]);
                    String tmpDownloadPassword = null;
                    if (downloadPasswords != null && downloadPasswords.length > 0) {
                        if (downloadPasswords.length == urls.length) {
                            tmpDownloadPassword = downloadPasswords[index];
                        } else {
                            tmpDownloadPassword = downloadPasswords[0];
                        }
                    }
                    final String downloadPassword = tmpDownloadPassword;

                    final int index2 = index;
                    link.setCustomCrawledLinkModifier(new CrawledLinkModifier() {

                        public void modifyCrawledLink(CrawledLink link) {
                            final DownloadLink dl = link.getDownloadLink();
                            if (dl != null) {
                                if (StringUtils.isNotEmpty(downloadPassword)) {
                                    dl.setDownloadPassword(downloadPassword);
                                }
                                if (StringUtils.isEmpty(dl.getReferrerUrl()) && StringUtils.isNotEmpty(referer)) {
                                    dl.setReferrerUrl(referer);
                                }
                                if (index2 < desc.length && StringUtils.isNotEmpty(desc[index2])) {
                                    dl.setComment(desc[index2]);
                                }
                                if (index2 < fnames.length && StringUtils.isNotEmpty(fnames[index2])) {
                                    dl.setForcedFileName(fnames[index2]);
                                }
                                if (StringUtils.isNotEmpty(cookies)) {
                                    dl.setProperty("cookies", cookies);
                                }
                                if (StringUtils.isNotEmpty(post)) {
                                    dl.setProperty("post", post);
                                }
                                if (StringUtils.isNotEmpty(referer)) {
                                    dl.setProperty("referer", referer);
                                }
                            }
                        }
                    });
                    link.setUnknownHandler(new UnknownCrawledLinkHandler() {

                        /*
                         * this handler transforms unknown links into directhttp links with all information given by flashgot
                         */
                        public void unhandledCrawledLink(CrawledLink link, LinkCrawler lc) {
                            String url = link.getURL();
                            String name = null;
                            try {
                                name = Plugin.extractFileNameFromURL(url);
                            } catch (final Throwable e) {
                                Log.exception(e);
                            }
                            final DownloadLink direct = new DownloadLink(defaultplg, name, "DirectHTTP", url, true);
                            if (StringUtils.isNotEmpty(downloadPassword)) {
                                direct.setDownloadPassword(downloadPassword);
                            }
                            if (index2 < desc.length && StringUtils.isNotEmpty(desc[index2])) {
                                direct.setComment(desc[index2]);
                            }
                            if (StringUtils.isNotEmpty(cookies)) {
                                direct.setProperty("cookies", cookies);
                            }
                            if (StringUtils.isNotEmpty(post)) {
                                direct.setProperty("post", post);
                            }
                            if (StringUtils.isNotEmpty(referer)) {
                                direct.setProperty("referer", referer);
                            }
                            if (index2 < fnames.length && StringUtils.isNotEmpty(fnames[index2])) {
                                direct.setProperty("fixName", fnames[index2]);
                            }
                            try {
                                defaultplg.correctDownloadLink(direct);
                            } catch (final Throwable e) {
                                Log.exception(e);
                            }
                            link.setDownloadLink(direct);
                        }
                    });
                    links.add(link);
                }
                LinkCollector.getInstance().addCrawlerJob(links, job);
            }
            writeString(response, request, sb.toString(), true);
        } catch (final Throwable e) {
            e.printStackTrace();
            throw new InternalApiException(e);
        }
    }

}
