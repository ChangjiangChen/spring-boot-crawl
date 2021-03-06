package com.htdata.crawl.core.task.impl;

import com.htdata.crawl.core.constant.CommonConfig;
import com.htdata.crawl.core.constant.ContentTypeEnum;
import com.htdata.crawl.core.dao.CrawlInfoDao;
import com.htdata.crawl.core.dao.ParamInfoDao;
import com.htdata.crawl.core.entity.CrawlInfoEntity;
import com.htdata.crawl.core.manager.JsoupParseManager;
import com.htdata.crawl.core.manager.UrlContainerManager;
import com.htdata.crawl.core.task.CrawlTaskService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手写爬虫内部管理，在研究透彻crawl4j之前用于补充crawl4j用于爬取可能的设置错误而不能爬取的网站
 */
@Slf4j
//@ConditionalOnProperty(name = CommonConfig.CRAWL_SERVICE_KEY, havingValue = CommonConfig.CRAWL_SERVICE_WITH_SIMPLECRAWL)
@Service(CommonConfig.CRAWL_SERVICE_WITH_SIMPLECRAWL)
public class SimpleCrawlServiceImpl implements CrawlTaskService {
    @Autowired
    private UrlContainerManager urlContainerManager;
    @Autowired
    private JsoupParseManager jsoupParseManager;
    @Autowired
    private CrawlInfoDao crawlInfoDao;
    @Autowired
    private ParamInfoDao paramInfoDao;

    private final Pattern patternLow = Pattern.compile(".*\\.(css|js|gif|jpg|png|mp3|mp3|zip|gz|pdf|doc|xls|docx|xlsx|rar|tif)$");
    //对于某些特殊的URL，提取出来会有分行符等，如果不进行转换会影响程序正常执行
    private final Pattern patternSpecialUrl = Pattern.compile("\\s*");
    //匹配所有汉字
    private final Pattern patternCharacter = Pattern.compile("[\u4e00-\u9fa5]");

    public void crawl() {
        //参数初始化
        String baseUrl = paramInfoDao.getWebUrl();
        initContainerManager();
        //抓的时候填，填完了新的一轮取出来用，用了就删掉
        List<String> childList = getUrlList(paramInfoDao.getSeedUrlList(), baseUrl);
        log.info("================第一轮种子获取完毕({})（第一轮获取url不进行判空处理，但子url与后续处理一致）================", childList);
        while (!childList.isEmpty()) {
            log.info("urlContainerManager.getHashSet().size()===>" + urlContainerManager.getHashSet().size());
            //查看url是否重复，重复则不处理，不重复的放入待处理List中
            //在处理不重复的url之前，先将url全部放入urlContainerManager中
            List<String> preparedList = new ArrayList<>();
            for (int i = 0; i < childList.size(); i++) {
                String url = childList.get(i);
                if (!urlContainerManager.urlExists(url)) {
                    preparedList.add(url);
                    urlContainerManager.storeUrlToSet(url);
                }
            }
            //处理url，获取doc，获取需要存储的内容，也获取下一轮需要处理的子url
            int count = 0;
            for (int i = 0; i < preparedList.size(); i++) {
                //第一步，获取url页面内容
                String url = preparedList.get(i);
                Document document;
                Connection connection = Jsoup.connect(url);
                try {
                    document = connection.get();
                } catch (IOException e) {
                    log.error("url（{}）在抓取时发生异常：{}", url, e.getMessage());
                    //如果本次的url未获取成功就放弃本次任务
                    continue;
                }
                count++;
                //第二步，分析link，去除重复内容，装入子childList中
                //先将之前childList中内容清空
                childList.clear();
                Elements link = document.select("a");
                for (Element element : link) {
                    //获取页面的url
                    String absHref = element.attr("abs:href");
                    //判定url合理性
                    if (!urlValid(absHref, baseUrl)) {
                        continue;
                    }
                    //转换中文和\s符号
                    String characterProcessedUrl = getCharacterProcessedUrl(absHref);
                    if (urlContainerManager.urlExists(characterProcessedUrl)) {
                        continue;
                    }
                    childList.add(characterProcessedUrl);
                }
                //第三步，数据解析存入数据库
                String html = document.body().toString();
                instoreData(html, url);
            }
            log.info("共处理了{}个网页,下一轮即将被处理的childList包含有效的url总计{}条", count, childList.size());
            if (childList.size() == 0) {
                log.info("childList.size()==0,本次爬取任务即将结束！");
            } else if (childList.size() == 1) {
                log.info("childList.size()==1的情况，查看url={}", childList.get(0));
            }
        }

    }

    private void initContainerManager() {
        String tableName = paramInfoDao.getDetailInfoTableName();
        //正式爬取之前，首先将历史纪录url放入已经爬取的列表中
        urlContainerManager.initContainerHashSet("url", tableName);
    }

    private void instoreData(String html, String url) {
        String detailedInfoTableName = paramInfoDao.getDetailInfoTableName();
        FastDateFormat actualFastDateFormat = FastDateFormat.getInstance(paramInfoDao.getTimeFormat());
        String title = jsoupParseManager.getTitleInfo(html, paramInfoDao.getTitleTag(), ContentTypeEnum.TEXT);
        String time = jsoupParseManager.getTimeInfo(html, paramInfoDao.getTimeTag(), ContentTypeEnum.TEXT,
                paramInfoDao.getTimeRegexPattern(), paramInfoDao.getTimeFormat(), actualFastDateFormat);
        String contentHtml = jsoupParseManager.getContentInfo(html, paramInfoDao.getContentTag(), ContentTypeEnum.HTML);
        if (StringUtils.isBlank(title) || StringUtils.isBlank(time) || StringUtils.isBlank(contentHtml)) {
            log.info("爬取内容/标题/时间为null --{}", url);
        } else if (title.length() > 200 || contentHtml.getBytes().length > 65535) {
            log.info("title or content too long ->{}--{}--{}", title, time, url);
        } else {
            log.info("start to store ->{}--{}--{}", title, time, url);
            CrawlInfoEntity crawlInfoEntity = new CrawlInfoEntity();
            crawlInfoEntity.setUrl(url);
            crawlInfoEntity.setBatch_id(Integer.parseInt(System.getProperty(CommonConfig.CRAWL_BATCH_ID_KEY)));
            crawlInfoEntity.setGmt_create(new Date());
            crawlInfoEntity.setGmt_modified(new Date());
            crawlInfoEntity.setCrawled_date(time);
            crawlInfoEntity.setCrawled_title(title);
            crawlInfoEntity.setCrawled_content_html(contentHtml);
            crawlInfoEntity.setArea(paramInfoDao.getAreaId());
            crawlInfoEntity.setIs_filtered(0);
            crawlInfoEntity.setCategory_id(paramInfoDao.getCategoryId());
            crawlInfoEntity.setCategory(paramInfoDao.getCategoryName());
            crawlInfoDao.insert(crawlInfoEntity, detailedInfoTableName);
        }
    }

    /**
     * 获取种子url抓取的第一批可处理url;
     * 返回为空list
     *
     * @param seedUrlList
     * @param baseUrl
     * @return
     */
    private List<String> getUrlList(List<String> seedUrlList, String baseUrl) {
        if (seedUrlList == null || seedUrlList.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> childList = new ArrayList<>();
        log.info("---------开始进行种子url抓取，本轮共{}个种子url-------------", seedUrlList.size());
        for (String seedUrl : seedUrlList) {
            //抓取页面
            Document doc;
            try {
                long start = System.currentTimeMillis();
                doc = Jsoup.connect(seedUrl).get();
                log.info("获取种子url：{}所花费的时间为{}ms", seedUrl, System.currentTimeMillis() - start);
                long internalStart = System.currentTimeMillis();
                //获取所有为a下的链接
                Elements link = doc.select("a");
                for (Element element : link) {
                    //获取页面的url
                    String absHref = element.attr("abs:href");
                    //判定url合理性
                    if (urlValid(absHref, baseUrl)) {
                        childList.add(getCharacterProcessedUrl(absHref));
                    }
                }
                log.info("link分析时长:{}ms", System.currentTimeMillis() - internalStart);
            } catch (Exception e) {
                log.error("种子url（{}）在抓取时发生异常：{}", seedUrl, e.getMessage());
            }
        }
        log.info("结束本轮种子url抓取:{}", System.currentTimeMillis());
        return childList;
    }

    /**
     * 判断准备存入或者爬取的url是否合理,合理则装入准备爬取的容器
     *
     * @param originalUrl
     * @param baseUrl
     * @return
     */
    private boolean urlValid(String originalUrl, String baseUrl) {
        if (originalUrl == null) {
            return false;
        }
        //如果url不是以本网站url开头的pass
        if (!originalUrl.startsWith(baseUrl)) {
            return false;
        }
        //有些特殊的url，会包含分割符，会通过之前的判断，但是很少见，所以单独处理之后再进行一次判断
        String processedUrl = patternSpecialUrl.matcher(originalUrl).replaceAll("").toLowerCase();
        //如果是各种文件(如doc,png,gif结尾等，无需进行后续爬取)，就pass掉该url
        if (patternLow.matcher(processedUrl).matches()) {
            return false;
        }
        return true;
    }

    /**
     * 处理装入容器的url
     *
     * @param originalUrl
     * @return
     */
    private String getCharacterProcessedUrl(String originalUrl) {
        String processedUrl = null;
        if (originalUrl == null) {
            return processedUrl;
        } else {
            processedUrl = originalUrl;
        }
        //处理汉字
        Matcher matcher = patternCharacter.matcher(processedUrl);
        while (matcher.find()) {
            processedUrl = processedUrl.replace(matcher.group(), URLEncoder.encode(matcher.group()));
        }
        return processedUrl;
    }

}
