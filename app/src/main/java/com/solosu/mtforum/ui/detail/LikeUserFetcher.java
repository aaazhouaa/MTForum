package com.solosu.mtforum.ui.detail;

import com.solosu.mtforum.network.HttpClient;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 点赞人真实昵称/头像 抓取器(build67)
 *
 * 详情页 DOM(ul.comiis_recommend_list_a) 只带头像没有昵称, 昵称必须走独立接口:
 *   https://bbs.binmt.cc/misc.php?op=recommend&tid={tid}&mod=faq&mobile=2
 * 免登录, 一次性返回全部点赞人, 实测结构:
 *   <div class="comiis_userlist bg_f cl"><ul>
 *     <li class="b_b"><a href="...home.php?mod=space&amp;uid=146665&amp;do=profile" class="kmdbt">
 *       <i class="comiis_font f_d">&#xe60c;</i>
 *       <img src="https://avatar.mt2.cn/uc_server/avatar.php?uid=146665&size=middle">雨山</a></li>
 *   </ul></div>
 * 即: uid 在 href、头像在 img、昵称是 a 标签内的纯文本(需剥掉图标字体的私用区字符)
 */
public class LikeUserFetcher {

    public static class Item {
        public final String uid;
        public final String name;
        public final String avatar;

        public Item(String uid, String name, String avatar) {
            this.uid = uid;
            this.name = name;
            this.avatar = avatar;
        }
    }

    private static final String BASE_DOMAIN = "https://bbs.binmt.cc/";
    private static final Pattern UID_PATTERN = Pattern.compile("uid=(\\d+)");
    /** 图标字体落在 Unicode 私用区(U+E000~U+F8FF), 属噪声需剥离 */
    private static final Pattern ICON_FONT_PATTERN = Pattern.compile("[\\uE000-\\uF8FF]");
    /** 零宽字符 / BOM */
    private static final Pattern ZERO_WIDTH_PATTERN = Pattern.compile("[\\u200B-\\u200F\\uFEFF]");

    /** 阻塞式抓取(必须放后台线程调用); 任何失败都返回空 list, 不抛异常 */
    public static List<Item> fetch(String tid) {
        List<Item> out = new ArrayList<>();
        if (tid == null || tid.isEmpty()) {
            return out;
        }
        try {
            String url = BASE_DOMAIN + "misc.php?op=recommend&tid=" + tid + "&mod=faq&mobile=2";
            String html = HttpClient.getInstance().get(url);
            if (html == null || html.isEmpty()) {
                return out;
            }
            Document doc = Jsoup.parse(html);
            Element box = doc.selectFirst("div.comiis_userlist");
            if (box == null) {
                return out;
            }
            Elements links = box.select("li a[href*=uid=]");
            for (Element a : links) {
                Matcher m = UID_PATTERN.matcher(a.attr("href"));
                if (!m.find()) {
                    continue;
                }
                String uid = m.group(1);
                Element img = a.selectFirst("img");
                String avatar = "";
                if (img != null) {
                    avatar = img.absUrl("src");
                    if (avatar.isEmpty()) {
                        avatar = img.attr("src");
                    }
                }
                if (!avatar.isEmpty() && avatar.startsWith("/") && !avatar.startsWith("//")) {
                    avatar = BASE_DOMAIN + avatar;
                }
                String name = cleanName(a.text());
                if (name.isEmpty()) {
                    name = "用户" + uid;
                }
                out.add(new Item(uid, name, avatar));
            }
        } catch (Exception e) {
            // 网络/解析失败静默返回空, 弹窗保留详情页原有数据
        }
        return out;
    }

    private static String cleanName(String s) {
        if (s == null) {
            return "";
        }
        s = ICON_FONT_PATTERN.matcher(s).replaceAll("");
        s = ZERO_WIDTH_PATTERN.matcher(s).replaceAll("");
        return s.trim();
    }
}