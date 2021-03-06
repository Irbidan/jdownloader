package jd.plugins.hoster;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "video.google.com" }, urls = { "http://(www\\.)?video\\.google\\.(com|de)/(videoplay\\?docid=|googleplayer\\.swf\\?autoplay=1\\&fs=true\\&fs=true\\&docId=)(\\-)?\\d+|https?://redirector\\.googlevideo\\.com/videoplayback\\?.+" }, flags = { 0 })
public class VideoGoogle extends PluginForHost {

    private String       dllink = null;
    private final String embed  = "https?://redirector\\.googlevideo\\.com/videoplayback\\?.+";

    public VideoGoogle(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.google.com/accounts/TOS?loc=ZZ&hl=en";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    public void correctDownloadLink(final DownloadLink downloadLink) {
        if (!downloadLink.getDownloadURL().matches(embed)) {
            downloadLink.setUrlDownload("http://video.google.com/videoplay?docid=" + new Regex(downloadLink.getDownloadURL(), "((\\-)?\\d+)$").getMatch(0));
        }
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        dl = jd.plugins.BrowserAdapter.openDownload(this.br, downloadLink, dllink, true, 0);
        if (!dl.getConnection().isContentDisposition() && !dl.getConnection().getContentType().startsWith("video")) {
            br.followConnection();
            if (br.containsHTML("No htmlCode read")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.videogoogle.videotemporaryunavailable", "This video is temporary unavailable!"), 60 * 60 * 1000l);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        this.setBrowserExclusive();
        if (downloadLink.getDownloadURL().matches(embed)) {
            dllink = downloadLink.getDownloadURL();
        } else {
            br.getPage(downloadLink.getDownloadURL());
            // Check this way because language of site is different for everyone
            if (!br.containsHTML("googleplayer\\.swf")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String videoID = new Regex(downloadLink.getDownloadURL(), "((\\-)?\\d+)$").getMatch(0);
            String name = br.getRegex("<title>(.*?)</title>").getMatch(0);
            if (name == null || "301 Moved".equals(name)) {
                name = videoID;
            }
            downloadLink.setFinalFileName(Encoding.htmlDecode(name) + ".flv");
            dllink = br.getRegex("videoUrl\\\\x3d(http://.*?\\.googlevideo\\.com/videoplayback.*?)\\\\x26thumbnailUrl").getMatch(0);
            if (dllink == null) {
                br.getPage("http://video.google.com/videofeed?fgvns=1&fai=1&docid=" + videoID);
                dllink = br.getRegex("<media:content url=\"(http://[a-z0-9\\.]+\\.googlevideo\\.com/[^<>\"]*?)\"").getMatch(0);
            }
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dllink = Encoding.htmlDecode(dllink);
            dllink = Encoding.urlDecode(dllink, true);
        }
        Browser br2 = br.cloneBrowser();
        // In case the link redirects to the finallink
        br2.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br2.openGetConnection(dllink);
            if (!con.getContentType().contains("html")) {
                downloadLink.setDownloadSize(con.getLongContentLength());
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            return AvailableStatus.TRUE;
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

}