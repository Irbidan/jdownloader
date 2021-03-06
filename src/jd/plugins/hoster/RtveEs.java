//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
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

package jd.plugins.hoster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.JDHexUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "rtve.es" }, urls = { "http://(www\\.)?rtve\\.es/alacarta/(audios|videos)/[\\w\\-]+/[\\w\\-]+/\\d+/?(\\?modl=COMTS)?" }, flags = { 0 })
public class RtveEs extends PluginForHost {

    private String DLURL       = null;
    private String BLOWFISHKEY = "eWVMJmRhRDM=";
    private String dl_now_now  = null;

    public RtveEs(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.rtve.es/comunes/aviso_legal.html";
    }

    private byte[] getBlowfish(byte[] value, boolean decrypt) {
        try {
            Cipher c = Cipher.getInstance("Blowfish/ECB/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(org.appwork.utils.encoding.Base64.decode(BLOWFISHKEY), "Blowfish");
            if (decrypt) {
                c.init(Cipher.DECRYPT_MODE, keySpec);
            } else {
                c.init(Cipher.ENCRYPT_MODE, keySpec);
            }
            byte[] result = c.doFinal(value);
            return result;
        } catch (Throwable e) {
            return null;
        }
    }

    private String getLink(String xml) {
        if (xml == null) {
            return null;
        }
        ArrayList<String> dllinks = new ArrayList<String>(Arrays.asList(new Regex(xml, "provider=\'[\\w\\-]+\'>([^<]+)").getColumn(0)));
        // Collections.shuffle(dllinks);
        for (String dllink : dllinks) {
            if (dllink.startsWith("rtmp") || dllink.endsWith("type=.smil")) {
                continue;
            }
            return dllink;
        }
        return null;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception {
        requestVideo(downloadLink);
        if (dl_now_now != null) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error: " + dl_now_now);
        }
        if (DLURL == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, DLURL, true, 0);
        if (dl.getConnection().getContentType().contains("html")) {
            dl.getConnection().disconnect();
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        dl.startDownload();
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        requestVideo(downloadLink);
        setBrowserExclusive();
        if (dl_now_now != null) {
            return AvailableStatus.TRUE;
        }
        try {
            if (!br.openGetConnection(DLURL).getContentType().contains("html")) {
                downloadLink.setDownloadSize(br.getHttpConnection().getLongContentLength());
                br.getHttpConnection().disconnect();
                return AvailableStatus.TRUE;
            }
        } finally {
            if (br.getHttpConnection() != null) {
                br.getHttpConnection().disconnect();
            }
        }
        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
    }

    @SuppressWarnings("deprecation")
    private AvailableStatus requestVideo(final DownloadLink downloadLink) throws IOException, PluginException {
        br.setFollowRedirects(true);
        String dllink = downloadLink.getDownloadURL();
        br.getPage(dllink);
        if (br.containsHTML("La página solicitada no está disponible por haber cambiado la dirección \\(URL\\) o no existir\\.")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = br.getRegex("<h1><span title=\"([^\"]+)").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("class=\"last\">([^<]+)").getMatch(0);
        }
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        filename = Encoding.htmlDecode(filename.trim());
        dl_now_now = br.getRegex(">(Lunes a jueves a las \\d{2}\\.\\d{2} y \\d{2}\\.\\d{2} horas)<").getMatch(0);
        if (dl_now_now != null) {
            downloadLink.getLinkStatus().setStatusText("Server error: " + dl_now_now);
            downloadLink.setName(filename + ".mp4");
            return AvailableStatus.TRUE;
        }

        String[] flashVars = br.getRegex("assetID=(\\d+)_([a-z]{2,3})_(audios|videos)\\&location=alacarta").getRow(0);
        if (flashVars == null || flashVars.length != 3) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /* encrypt request query */
        String getEncData = org.appwork.utils.encoding.Base64.encodeToString(getBlowfish(JDHexUtils.getByteArray(JDHexUtils.getHexString(flashVars[0] + "_default_" + ("audios".equals(flashVars[2]) ? "audio" : "video") + "_" + flashVars[1])), false), false);
        getEncData = getEncData.replaceAll("/", "_");
        Browser enc = br.cloneBrowser();
        enc.getPage("http://ztnr.rtve.es/ztnr/res/" + getEncData);
        /* Check for empty page */
        if (enc.toString().length() <= 22) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* decrypt response body */
        DLURL = getLink(JDHexUtils.toString(JDHexUtils.getHexString(getBlowfish(org.appwork.utils.encoding.Base64.decode(enc.toString()), true))));

        if (DLURL == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        String ext = DLURL.substring(DLURL.lastIndexOf("."));
        ext = ext == null || ext.length() > 4 ? ".mp4" : ext;
        downloadLink.setName(filename + ext);
        return AvailableStatus.TRUE;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {
    }

}