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

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "clipfish.de" }, urls = { "http://(www\\.)?clipfish\\.de/(.*?channel/\\d+/video/\\d+|video/\\d+(/.+)?|special/.*?/video/\\d+|musikvideos/video/\\d+(/.+)?)" }, flags = { 0 })
public class ClpfshD extends PluginForDecrypt {

    private final Pattern PATTERN_CAHNNEL_VIDEO  = Pattern.compile("http://[w\\.]+?clipfish\\.de/.*?channel/\\d+/video/(\\d+)");
    private final Pattern PATTERN_MUSIK_VIDEO    = Pattern.compile("http://[w\\.]+?clipfish\\.de/musikvideos/video/(\\d+)(/.+)?");
    private final Pattern PATTERN_STANDARD_VIDEO = Pattern.compile("http://[w\\.]+?clipfish\\.de/video/(\\d+)(/.+)?");
    private final Pattern PATTERN_SPECIAL_VIDEO  = Pattern.compile("http://[w\\.]+?clipfish\\.de/special/.*?/video/(\\d+)");
    private final Pattern PATTERN_FLV_FILE       = Pattern.compile("&url=(http://.+?\\....)&|<filename><\\!\\[CDATA\\[(.*?)\\]\\]></filename>", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_TITEL          = Pattern.compile("<meta property=\"og:title\" content=\"(.+?)\"/>", Pattern.CASE_INSENSITIVE);

    private final String  NEW_XMP_PATH           = "http://www.clipfish.de/devxml/videoinfo/";

    public ClpfshD(final PluginWrapper wrapper) {
        super(wrapper);
    }

    /* Tags: rtl-interactive.de */

    @SuppressWarnings("deprecation")
    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink cryptedLink, final ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = cryptedLink.getCryptedUrl();
        br.clearCookies(getHost());
        br.setFollowRedirects(true);
        URLConnectionAdapter con = br.openGetConnection(parameter);
        if (con.getResponseCode() == 410) {
            con.disconnect();
            final DownloadLink offline = createDownloadlink("directhttp://" + parameter);
            offline.setAvailable(false);
            offline.setProperty("offline", true);
            decryptedLinks.add(offline);
            return decryptedLinks;
        }
        final Regex regexInfo = new Regex(br.followConnection(), PATTERN_TITEL);
        if (br.getURL().contains("clipfish.de/special/cfhome/home/")) {
            logger.info("Link offline: " + parameter);
            final DownloadLink offline = createDownloadlink("directhttp://" + parameter);
            offline.setAvailable(false);
            offline.setProperty("offline", true);
            decryptedLinks.add(offline);
            return decryptedLinks;
        }
        if (!br.containsHTML("CFAdBrandedPlayer\\.js") && !br.containsHTML("CFPlayerBasic(\\.min)?\\.js")) {
            logger.info("Link offline/unsupported: " + parameter);
            final DownloadLink offline = createDownloadlink("directhttp://" + parameter);
            offline.setAvailable(false);
            offline.setProperty("offline", true);
            decryptedLinks.add(offline);
            return decryptedLinks;
        }
        String tmpStr = regexInfo.getMatch(0);
        if (tmpStr == null) {
            tmpStr = br.getRegex("<h2>([^<]+)</h2>").getMatch(0);
            if (tmpStr != null) {
                tmpStr = br.getRegex("title=\"(" + tmpStr + "[^\"]+)\"").getMatch(0);
            }
        }
        if (tmpStr == null) {
            final DownloadLink offline = createDownloadlink("directhttp://" + parameter);
            offline.setAvailable(false);
            offline.setProperty("offline", true);
            decryptedLinks.add(offline);
            return decryptedLinks;
        }
        String name = tmpStr.substring(0, tmpStr.lastIndexOf("-"));
        String cType = tmpStr.substring(tmpStr.lastIndexOf("-") + 1, tmpStr.length()).toLowerCase(Locale.ENGLISH);
        if (name == null || cType == null) {
            return null;
        }
        cType = cType.trim();

        String vidId = null;
        if (new Regex(parameter, PATTERN_STANDARD_VIDEO).matches()) {
            vidId = new Regex(parameter, PATTERN_STANDARD_VIDEO).getMatch(0);
        } else if (new Regex(parameter, PATTERN_CAHNNEL_VIDEO).matches()) {
            vidId = new Regex(parameter, PATTERN_CAHNNEL_VIDEO).getMatch(0);
        } else if (new Regex(parameter, PATTERN_SPECIAL_VIDEO).matches()) {
            vidId = br.getRegex("vid\\s*:\\s*\"(\\d+)\"").getMatch(0);
        } else if (new Regex(parameter, PATTERN_MUSIK_VIDEO).matches()) {
            vidId = new Regex(parameter, PATTERN_MUSIK_VIDEO).getMatch(0);
        } else {
            logger.severe("No VidID found");
            return decryptedLinks;
        }

        String flashplayer = br.getRegex("(clipfish_player_\\d+\\.swf)").getMatch(0);

        String spc = br.getRegex("specialId\\s*=\\s*(\\d+)").getMatch(0);
        int specialID = spc != null ? Integer.parseInt(spc) : -1;
        // specialID

        /*
         * Example http url: http://video.clipfish.de/media/<vid_url_id>/<videohash>.mp4 Videohashes can also be found in pic urls:
         * /autoimg/<videohash> /
         */
        /* Example HDS url (first req): http://hds.fra.clipfish.de/hds-vod-enc/media/<vid_url_id>/<videohash>.mp4.f4m */

        String pathToflv = br.getRegex("videourl: \"(http[^<>\"]*?)\"").getMatch(0);
        if (pathToflv == null) {
            if (specialID >= 0) {
                br.getPage(NEW_XMP_PATH + vidId + "/" + specialID + "/" + "?ts=" + System.currentTimeMillis());
            } else {
                br.getPage(NEW_XMP_PATH + vidId + "/" + "?ts=" + System.currentTimeMillis());
            }
            String page = br.toString();
            pathToflv = getDllink(page);
            if (pathToflv.startsWith("rtmp")) {
                String dataPath = new Regex(pathToflv, "(media/.+?\\.mp4)").getMatch(0);
                if (dataPath != null) {
                    pathToflv = "http://video.clipfish.de/" + dataPath;
                }

            }
        }

        final DownloadLink downloadLink = createDownloadlink("clipfish2://" + parameter.replaceFirst("http://", ""));
        downloadLink.setProperty("dlURL", pathToflv);
        downloadLink.setProperty("MEDIA_TYPE", cType);
        downloadLink.setProperty("VIDEO_ID", vidId);
        name = Encoding.htmlDecode(name.trim());

        String ext = pathToflv.substring(pathToflv.lastIndexOf(".") + 1, pathToflv.length());
        if (pathToflv.startsWith("rtmp")) {
            ext = new Regex(pathToflv, "(\\w+):media/").getMatch(0);
            ext = ext.length() > 3 ? null : ext;
            if (flashplayer != null) {
                downloadLink.setProperty("FLASHPLAYER", "http://www.clipfish.de/cfng/flash/" + flashplayer);
            }
        }

        ext = ext == null || ext.equals("f4v") || ext.equals("") ? "mp4" : ext;
        downloadLink.setFinalFileName(name + "." + ext);
        decryptedLinks.add(downloadLink);

        return decryptedLinks;
    }

    private String getDllink(final String page) {
        String out = null;
        final String[] allContent = new Regex(page, PATTERN_FLV_FILE).getRow(0);
        for (final String c : allContent) {
            if (c != null) {
                out = c;
                break;
            }
        }
        return out;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

}