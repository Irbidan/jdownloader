//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.decrypter;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Random;

import jd.PluginWrapper;
import jd.config.SubConfiguration;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.Browser.BrowserException;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;
import jd.utils.JDUtilities;

import org.appwork.utils.formatter.TimeFormatter;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "ardmediathek.de", "rbb-online.de" }, urls = { "http://(?:www\\.)?(?:ardmediathek|mediathek\\.daserste)\\.de/.+|http://www\\.daserste\\.de/[^<>\"]+/(videos|videosextern)/[a-z0-9\\-]+\\.html", "http://(?:www\\.)?mediathek\\.rbb\\-online\\.de/tv/[^<>\"]+documentId=\\d+[^<>\"/]+bcastId=\\d+" }, flags = { 32, 32 })
public class RDMdthk extends PluginForDecrypt {

    /* Settings */
    private static final String                 Q_LOW                 = "Q_LOW";
    private static final String                 Q_MEDIUM              = "Q_MEDIUM";
    private static final String                 Q_HIGH                = "Q_HIGH";
    private static final String                 Q_HD                  = "Q_HD";
    private static final String                 Q_BEST                = "Q_BEST";
    private static final String                 Q_HTTP_ONLY           = "Q_HTTP_ONLY";
    private static final String                 Q_SUBTITLES           = "Q_SUBTITLES";
    private boolean                             BEST                  = false;
    private boolean                             HTTP_ONLY             = false;
    private int                                 notForStable          = 0;
    private static final String                 EXCEPTION_LINKOFFLINE = "EXCEPTION_LINKOFFLINE";

    /* Constants */
    private static final String                 AGE_RESTRICTED        = "(Diese Sendung ist für Jugendliche unter \\d+ Jahren nicht geeignet\\. Der Clip ist deshalb nur von \\d+ bis \\d+ Uhr verfügbar\\.)";
    private static final String                 type_unsupported      = "http://(www\\.)?ardmediathek\\.de/(tv/live\\?kanal=\\d+|dossiers/.*)";
    private static final String                 type_invalid          = "http://(www\\.)?(ardmediathek|mediathek\\.daserste)\\.de/(download|livestream).+";
    private static final String                 type_ard_mediathek    = "http://(www\\.)?(ardmediathek|mediathek\\.daserste)\\.de/.+";
    private static final String                 type_ardvideo         = "http://www\\.daserste\\.de/.+";
    private static final String                 type_rbb_mediathek    = "http://(?:www\\.)?mediathek\\.rbb\\-online\\.de/tv/[^<>\"]+documentId=\\d+[^<>\"/]+bcastId=\\d+";
    private SubConfiguration                    cfg                   = null;

    /* Variables */
    private final ArrayList<DownloadLink>       newRet                = new ArrayList<DownloadLink>();
    private final HashMap<String, DownloadLink> bestMap               = new HashMap<String, DownloadLink>();
    private String                              subtitleLink          = null;
    private boolean                             grab_subtitle         = false;
    private String                              parameter             = null;
    private String                              title                 = null;
    private String                              date                  = null;
    private String                              date_formatted        = null;
    ArrayList<DownloadLink>                     decryptedLinks        = new ArrayList<DownloadLink>();

    public RDMdthk(final PluginWrapper wrapper) {
        super(wrapper);
    }

    /**
     * Examples of other, unsupported linktypes:
     *
     * http://daserste.ndr.de/panorama/aktuell/Mal-eben-die-Welt-retten-Studie-belegt-Gefahren-durch-Voluntourismus-,volontourismus136.html
     *
     * */
    @SuppressWarnings("deprecation")
    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, final ProgressController progress) throws Exception {
        cfg = SubConfiguration.getConfig("ard.de");
        BEST = cfg.getBooleanProperty(Q_BEST, false);
        HTTP_ONLY = cfg.getBooleanProperty(Q_HTTP_ONLY, false);
        grab_subtitle = cfg.getBooleanProperty(Q_SUBTITLES, false);
        boolean offline = false;
        String fsk = null;
        parameter = Encoding.htmlDecode(param.toString());

        if (parameter.matches(type_unsupported) || parameter.matches(type_invalid)) {
            decryptedLinks.add(getOffline(parameter));
            return decryptedLinks;
        }
        br.setFollowRedirects(true);
        try {
            br.getPage(parameter);
        } catch (final BrowserException e) {
            offline = true;
        }
        if (offline) {
            decryptedLinks.add(getOffline(parameter));
            return decryptedLinks;
        }
        try {
            if (br.getURL().matches(type_ard_mediathek) || parameter.matches(type_rbb_mediathek)) {
                fsk = br.getRegex(AGE_RESTRICTED).getMatch(0);
                decryptMediathek();
            } else {
                decryptDasersteVideo();
            }
        } catch (final DecrypterException e) {
            try {
                if (e.getMessage().equals(EXCEPTION_LINKOFFLINE)) {
                    decryptedLinks.add(getOffline(parameter));
                    return decryptedLinks;
                }
            } catch (final Exception x) {
            }
            throw e;
        }

        if (decryptedLinks == null || decryptedLinks.size() == 0) {
            if (notForStable > 0) {
                logger.info("ARD-Mediathek: Only flash content is available. Not downloadable with JD1, please use JD2!");
                return decryptedLinks;
            }
            if (fsk != null) {
                logger.info("ARD-Mediathek: " + fsk);
                return decryptedLinks;
            }
            logger.warning("Decrypter out of date for link: " + parameter);
            return null;
        }
        return decryptedLinks;
    }

    /* INFORMATION: network = akamai or limelight == RTMP */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void decryptMediathek() throws Exception {
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        String show = br.getRegex("name=\"dcterms\\.isPartOf\" content=\"([^<>\"]*?)\"").getMatch(0);
        title = br.getRegex("<meta name=\"dcterms\\.title\" content=\"([^\"]+)\"").getMatch(0);
        final String realBaseUrl = new Regex(br.getBaseURL(), "(^.*\\.de)").getMatch(0);
        String broadcastID;
        if (parameter.matches("http://(www\\.)?mediathek\\.daserste\\.de/topvideos/[a-z0-9\\-_]+")) {
            broadcastID = new Regex(parameter, "/topvideos/(\\d+)").getMatch(0);
        } else {
            // ardmediathek.de
            broadcastID = new Regex(br.getURL(), "\\?documentId=(\\d+)").getMatch(0);
            // mediathek.daserste.de
            if (broadcastID == null) {
                broadcastID = new Regex(br.getURL(), realBaseUrl + "/[^/]+/[^/]+/(\\d+)").getMatch(0);
            }
            if (broadcastID == null) {
                broadcastID = new Regex(br.getURL(), realBaseUrl + "/suche/(\\d+)").getMatch(0);
            }
        }
        if (broadcastID == null) {
            logger.info("ARDMediathek: MediaID is null! link offline?");
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        if (br.containsHTML("(<h1>Leider konnte die gew\\&uuml;nschte Seite<br />nicht gefunden werden\\.</h1>|Die angeforderte Datei existiert leider nicht)")) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        this.date = br.getRegex("Video der Sendung vom (\\d{2}\\.\\d{2}\\.\\d{4})").getMatch(0);
        if (this.date == null) {
            this.date = br.getRegex("class=\"subtitle\">([^<>\"]*?) \\| ").getMatch(0);
        }
        final String original_ard_ID = broadcastID;
        if (title == null) {
            title = getTitle(br);
        }
        title = Encoding.htmlDecode(title).trim();
        title = encodeUnicode(title);
        if (show != null) {
            show = Encoding.htmlDecode(show).trim();
            show = encodeUnicode(show);
            title = show + " - " + title;
        }
        if (this.date != null) {
            this.date_formatted = formatDateArdMediathek(this.date);
            title = this.date_formatted + "_ard_" + title;
        }
        final Browser br = new Browser();
        setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage("http://www.ardmediathek.de/play/media/" + original_ard_ID + "?devicetype=pc&features=flash");
        /* No json --> No media to crawl! */
        if (!br.getHttpConnection().getContentType().contains("application/json")) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        subtitleLink = getJson("_subtitleUrl", br.toString());
        int t = 0;

        final String extension = ".mp4";
        final LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) jd.plugins.hoster.DummyScriptEnginePlugin.jsonToJavaObject(br.toString());
        final ArrayList<Object> _mediaArray = (ArrayList) entries.get("_mediaArray");
        final LinkedHashMap<String, Object> _mediaArray_lastentry = (LinkedHashMap<String, Object>) _mediaArray.get(_mediaArray.size() - 1);
        final ArrayList<Object> mediaStreamArray = (ArrayList) _mediaArray_lastentry.get("_mediaStreamArray");

        for (final Object stream : mediaStreamArray) {
            String directlink = null;
            final LinkedHashMap<String, Object> streammap = (LinkedHashMap<String, Object>) stream;
            final String server = (String) streammap.get("_server");
            String network = (String) streammap.get("_cdn");
            /* Basically we change it for the filename */
            if (network == null) {
                network = "default_nonetwork";
            }
            /*
             * Sometimes one quality has multiple streams/sub-qualities --> Usually one qualits is missing in the main array so let's "fix"
             * that. Happens e.g. for documentId: 24157750
             */
            int quality = ((Number) streammap.get("_quality")).intValue();
            if (streammap.get("_stream") instanceof ArrayList) {
                final ArrayList<Object> streamArray = (ArrayList) streammap.get("_stream");
                directlink = (String) streamArray.get(0);
                /* Add the sub-type stream as current quality (in case user wants that) */
                if (!userWantsQuality(quality)) {
                    continue;
                }
                addQuality(network, title, extension, false, (String) streamArray.get(1), quality, t, parameter);
                /* Move current quality one up to correct this */
                quality++;
            } else {
                directlink = (String) streammap.get("_stream");
            }
            // rtmp --> hds or rtmp
            final boolean isRTMP = (server != null && !server.equals("") && server.startsWith("rtmp://")) && !directlink.startsWith("http");
            /* Skip HDS */
            if (directlink.endsWith("manifest.f4m")) {
                continue;
            }
            /* Skip unneeded playlists */
            if ("default".equals(network) && directlink.endsWith("m3u")) {
                continue;
            }
            /* Server needed for rtmp links */
            if (!directlink.startsWith("http://") && isEmpty(server)) {
                continue;
            }

            directlink += "@";
            // rtmp t=?
            if (isRTMP) {
                directlink = server + "@" + directlink.split("\\?")[0];
            }
            // only http streams for old stable
            if (isRTMP && isStableEnviroment()) {
                notForStable++;
                continue;
            }
            /* Skip rtmp streams if user wants http only */
            if (isRTMP && HTTP_ONLY) {
                continue;
            }

            if (!userWantsQuality(Integer.valueOf(quality))) {
                continue;
            }

            addQuality(network, title, extension, isRTMP, directlink, quality, t, parameter);
        }
        findBEST();
        return;
    }

    private boolean userWantsQuality(final int quality) {
        switch (quality) {
        case 0:
            if ((cfg.getBooleanProperty(Q_LOW, true) || BEST) == false) {
                return false;
            }
            return true;
        case 1:
            if ((cfg.getBooleanProperty(Q_MEDIUM, true) || BEST) == false) {
                return false;
            }
            return true;
        case 2:
            if ((cfg.getBooleanProperty(Q_HIGH, true) || BEST) == false) {
                return false;
            }
            return true;
        case 3:
            if ((cfg.getBooleanProperty(Q_HD, true) || BEST) == false) {
                return false;
            }
            return true;
        default:
            /* E.g. unsupported */
            return false;
        }
    }

    /* Make fmt String out of quality Integer */
    private String getFMT(final int quality) {
        String fmt = null;
        switch (quality) {
        case 0:
            fmt = "low";
            break;
        case 1:
            fmt = "medium";
            break;
        case 2:
            fmt = "high";
            break;
        case 3:
            fmt = "hd";
            break;
        }
        return fmt;
    }

    /* INFORMATION: network = akamai or limelight == RTMP */
    private void decryptDasersteVideo() throws IOException, DecrypterException {
        final String xml_URL = parameter.replace(".html", "~playerXml.xml");
        setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(xml_URL);
        if (br.getHttpConnection().getResponseCode() == 404 || !br.getHttpConnection().getContentType().equals("application/xml")) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        this.date = getXML("broadcastDate");
        title = getXML("shareTitle");
        if (this.title == null || this.date == null) {
            throw new DecrypterException("Decrypter broken");
        }
        title = Encoding.htmlDecode(title).trim();
        title = encodeUnicode(title);
        this.date_formatted = formatDateDasErste(this.date);
        title = this.date_formatted + "_daserste_" + title;
        /* TODO: Implement this */
        subtitleLink = null;
        int t = 0;

        final String extension = ".mp4";
        final String[] mediaStreamArray = br.getRegex("(<asset type=\".*?</asset>)").getColumn(0);

        for (final String stream : mediaStreamArray) {
            final String assettype = new Regex(stream, "<asset type=\"([^<>\"]*?)\">").getMatch(0);
            final String server = null;
            final String network = "default";
            final int quality = this.convertASSETTYPEtoQuality(assettype);
            // rtmp --> hds or rtmp
            String directlink = getXML(stream, "fileName");
            final boolean isRTMP = (server != null && !server.equals("") && server.startsWith("rtmp://")) && !directlink.startsWith("http");
            /* Skip HDS */
            if (directlink.endsWith("manifest.f4m")) {
                continue;
            }
            /* Skip unneeded playlists */
            if ("default".equals(network) && directlink.endsWith("m3u")) {
                continue;
            }
            /* Server needed for rtmp links */
            if (!directlink.startsWith("http://") && isEmpty(server)) {
                continue;
            }

            directlink += "@";
            // rtmp t=?
            if (isRTMP) {
                directlink = server + "@" + directlink.split("\\?")[0];
            }
            // only http streams for old stable
            if (isRTMP && isStableEnviroment()) {
                notForStable++;
                continue;
            }
            /* Skip rtmp streams if user wants http only */
            if (isRTMP && HTTP_ONLY) {
                continue;
            }

            if (!userWantsQuality(Integer.valueOf(quality))) {
                continue;
            }

            addQuality(network, title, extension, isRTMP, directlink, quality, t, parameter);
        }
        findBEST();
        return;
    }

    /* Converts asset-type Strings from daserste.de video to the same Integer values used for their Mediathek * */
    private int convertASSETTYPEtoQuality(final String assettype) {
        int quality;
        if (assettype.equals("1.65 Web S VOD adaptive streaming")) {
            quality = 0;
        } else if (assettype.equals("1.63 Web M VOD adaptive streaming") || assettype.equals("1.24 Web M VOD")) {
            quality = 1;
        } else if (assettype.equals("1.71 ADS 4 VOD adaptive streaming")) {
            quality = 2;
        } else if (assettype.equals("1.69 Web L VOD adative streaming")) {
            quality = 3;
        } else {
            quality = -1;
        }
        return quality;
    }

    @SuppressWarnings("deprecation")
    private void findBEST() {
        String lastQualityFMT = null;
        if (newRet.size() > 0) {
            if (BEST) {
                /* only keep best quality */
                DownloadLink keep = bestMap.get("hd");
                if (keep == null) {
                    lastQualityFMT = "HIGH";
                    keep = bestMap.get("high");
                }
                if (keep == null) {
                    lastQualityFMT = "MEDIUM";
                    keep = bestMap.get("medium");
                }
                if (keep == null) {
                    lastQualityFMT = "LOW";
                    keep = bestMap.get("low");
                }
                if (keep != null) {
                    newRet.clear();
                    newRet.add(keep);

                    /* We have to re-add the subtitle for the best quality if wished by the user */
                    if (grab_subtitle && subtitleLink != null && !isEmpty(subtitleLink)) {
                        final String plain_name = keep.getStringProperty("plain_name", null);
                        final String orig_streamingtype = keep.getStringProperty("streamingType", null);
                        final String linkid = plain_name + "_" + orig_streamingtype;
                        final String plain_quality_part = keep.getStringProperty("plain_quality_part", null);
                        final String subtitle_filename = plain_name + ".xml";
                        final String finallink = "http://www.ardmediathek.de" + subtitleLink + "@" + plain_quality_part;
                        final DownloadLink dl_subtitle = createDownloadlink("http://ardmediathekdecrypted/" + System.currentTimeMillis() + new Random().nextInt(1000000000));
                        dl_subtitle.setAvailable(true);
                        dl_subtitle.setFinalFileName(subtitle_filename);
                        dl_subtitle.setProperty("directURL", finallink);
                        dl_subtitle.setProperty("directName", subtitle_filename);
                        dl_subtitle.setProperty("streamingType", "subtitle");
                        dl_subtitle.setProperty("mainlink", parameter);
                        try {
                            /* JD2 only */
                            dl_subtitle.setContentUrl(parameter);
                            dl_subtitle.setLinkID(linkid);
                        } catch (Throwable e) {
                            /* Stable */
                            dl_subtitle.setBrowserUrl(parameter);
                        }
                        newRet.add(dl_subtitle);
                    }

                }
            }
            if (newRet.size() > 1) {
                FilePackage fp = FilePackage.getInstance();
                fp.setName(title);
                fp.addLinks(newRet);
            }
            decryptedLinks = newRet;
        }
    }

    @SuppressWarnings("deprecation")
    private void addQuality(final String network, final String title, final String extension, final boolean isRTMP, final String url, final int quality_int, final int t, final String orig_link) {
        final String fmt = getFMT(quality_int);
        final String quality_part = fmt.toUpperCase(Locale.ENGLISH) + "-" + network;
        final String plain_name = title + "@" + quality_part;
        final String full_name = plain_name + extension;

        String linkid = plain_name + "_" + t;
        final DownloadLink link = createDownloadlink("http://ardmediathekdecrypted/" + System.currentTimeMillis() + new Random().nextInt(1000000000));
        /* RTMP links have no filesize anyways --> No need to check them in host plugin */
        if (isRTMP) {
            link.setAvailable(true);
        }
        link.setFinalFileName(full_name);
        try {
            /* JD2 only */
            link.setContentUrl(orig_link);
            link.setLinkID(linkid);
        } catch (Throwable e) {/* Stable */
            link.setBrowserUrl(orig_link);
        }
        if (this.date != null) {
            link.setProperty("date", this.date);
        }
        link.setProperty("directURL", url);
        link.setProperty("directName", full_name);
        link.setProperty("plain_name", plain_name);
        link.setProperty("plain_quality_part", quality_part);
        link.setProperty("plain_name", plain_name);
        link.setProperty("plain_network", network);
        link.setProperty("directQuality", Integer.toString(quality_int));
        link.setProperty("streamingType", t);
        link.setProperty("mainlink", orig_link);

        /* Add subtitle link for every quality so players will automatically find it */
        if (grab_subtitle && subtitleLink != null && !isEmpty(subtitleLink)) {
            linkid = plain_name + "_subtitle_" + t;
            final String subtitle_filename = plain_name + ".xml";
            final String finallink = "http://www.ardmediathek.de" + subtitleLink + "@" + quality_part;
            final DownloadLink dl_subtitle = createDownloadlink("http://ardmediathekdecrypted/" + System.currentTimeMillis() + new Random().nextInt(1000000000));
            try {
                /* JD2 only */
                dl_subtitle.setContentUrl(orig_link);
                link.setLinkID(linkid);
            } catch (Throwable e) {/* Stable */
                dl_subtitle.setBrowserUrl(orig_link);
            }
            dl_subtitle.setAvailable(true);
            dl_subtitle.setFinalFileName(subtitle_filename);
            dl_subtitle.setProperty("directURL", finallink);
            dl_subtitle.setProperty("directName", subtitle_filename);
            dl_subtitle.setProperty("streamingType", "subtitle");
            dl_subtitle.setProperty("mainlink", orig_link);
            newRet.add(dl_subtitle);
        }

        final DownloadLink best = bestMap.get(fmt);
        if (best == null || link.getDownloadSize() > best.getDownloadSize()) {
            bestMap.put(fmt, link);
        }
        newRet.add(link);
    }

    /*
     * Special workaround for HDS only streams.
     */
    private String fixWDRdirectlink(final String mainsource, String wdrlink) {
        final Regex wdrwtf = new Regex(wdrlink, "http://ondemand-de.wdr.de/medstdp/(fsk\\d+)/(\\d+)/(\\d+)/([0-9_]+)\\.mp4");
        String region = new Regex(mainsource, "adaptiv\\.wdr\\.de/[a-z0-9]+/medstdp/([a-z]{2})/").getMatch(0);
        final String fsk = wdrwtf.getMatch(0);
        final String number = wdrwtf.getMatch(1);
        final String main_id = wdrwtf.getMatch(2);
        final String quality_id = wdrwtf.getMatch(3);
        if (region != null && fsk != null && number != null && main_id != null && quality_id != null) {
            region = jd.plugins.decrypter.WdrDeDecrypt.correctRegionString(region);
            wdrlink = "http://http-ras.wdr.de/CMS2010/mdb/ondemand/" + region + "/" + fsk + "/" + number + "/" + main_id + "/" + quality_id + ".mp4";
        }
        return wdrlink;
    }

    /** Avoid chars which are not allowed in filenames under certain OS' */
    private static String encodeUnicode(final String input) {
        String output = input;
        output = output.replace(":", ";");
        output = output.replace("|", "¦");
        output = output.replace("<", "[");
        output = output.replace(">", "]");
        output = output.replace("/", "⁄");
        output = output.replace("\\", "∖");
        output = output.replace("*", "#");
        output = output.replace("?", "¿");
        output = output.replace("!", "¡");
        output = output.replace("\"", "'");
        return output;
    }

    private DownloadLink getOffline(final String parameter) {
        final DownloadLink dl = createDownloadlink("directhttp://" + parameter);
        dl.setAvailable(false);
        dl.setProperty("offline", true);
        return dl;
    }

    private String getTitle(final Browser br) {
        String title = br.getRegex("<(div|span) class=\"(MainBoxHeadline|BoxHeadline)\">([^<]+)</").getMatch(2);
        String titleUT = br.getRegex("<span class=\"(BoxHeadlineUT|boxSubHeadline)\">([^<]+)</").getMatch(1);
        if (titleUT == null) {
            titleUT = br.getRegex("<h3 class=\"mt\\-title\"><a>([^<>\"]*?)</a></h3>").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("<title>ard\\.online \\- Mediathek: ([^<]+)</title>").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("<h2>(.*?)</h2>").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("class=\"mt\\-icon mt\\-icon_video\"></span><img src=\"[^\"]+\" alt=\"([^\"]+)\"").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("class=\"mt\\-icon mt\\-icon\\-toggle_arrows\"></span>([^<>\"]*?)</a>").getMatch(0);
        }
        if (title != null) {
            title = Encoding.htmlDecode(title + (titleUT != null ? "__" + titleUT.replaceAll(":$", "") : "").trim());
        }
        if (title == null) {
            title = "UnknownTitle_" + System.currentTimeMillis();
        }
        title = title.replaceAll("\\n|\\t|,", "").trim();
        return title;
    }

    private String getXML(final String parameter) {
        return getXML(this.br.toString(), parameter);
    }

    private String getXML(final String source, final String parameter) {
        return new Regex(source, "<" + parameter + "[^<]*?>([^<>]*?)</" + parameter + ">").getMatch(0);
    }

    private String getJson(final String parameter, final String source) {
        String result = new Regex(source, "\"" + parameter + "\":([0-9\\.]+)").getMatch(0);
        if (result == null) {
            result = new Regex(source, "\"" + parameter + "\":\"([^<>\"]*?)\"").getMatch(0);
        }
        return result;
    }

    private boolean isEmpty(String ip) {
        return ip == null || ip.trim().length() == 0;
    }

    private boolean isStableEnviroment() {
        String prev = JDUtilities.getRevision();
        if (prev == null || prev.length() < 3) {
            prev = "0";
        } else {
            prev = prev.replaceAll(",|\\.", "");
        }
        final int rev = Integer.parseInt(prev);
        if (rev < 10000) {
            return true;
        }
        return false;
    }

    private String formatDateArdMediathek(final String input) {
        final long date = TimeFormatter.getMilliSeconds(input, "dd.MM.yyyy", Locale.GERMAN);
        String formattedDate = null;
        final String targetFormat = "yyyy-MM-dd";
        Date theDate = new Date(date);
        try {
            final SimpleDateFormat formatter = new SimpleDateFormat(targetFormat);
            formattedDate = formatter.format(theDate);
        } catch (Exception e) {
            /* prevent input error killing plugin */
            formattedDate = input;
        }
        return formattedDate;
    }

    private String formatDateDasErste(String input) {
        /* 2015-06-23T20:15:00.000+02:00 --> 2015-06-23T20:15:00.000+0200 */
        input = input.substring(0, input.lastIndexOf(":")) + "00";
        final long date = TimeFormatter.getMilliSeconds(input, "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.GERMAN);
        String formattedDate = null;
        final String targetFormat = "yyyy-MM-dd";
        Date theDate = new Date(date);
        try {
            final SimpleDateFormat formatter = new SimpleDateFormat(targetFormat);
            formattedDate = formatter.format(theDate);
        } catch (Exception e) {
            /* prevent input error killing plugin */
            formattedDate = input;
        }
        return formattedDate;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

}