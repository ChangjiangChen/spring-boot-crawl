package com.htdata.crawl.web.controller;

import com.alibaba.fastjson.JSONObject;

import com.htdata.crawl.core.manager.HttpUtil;
import com.htdata.crawl.core.constant.CommonConfig;
import com.htdata.crawl.core.service.JsoupParseServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/testJob")
public class TestJobController {

    @Autowired
    private HttpUtil httpUtil;
    @Autowired
    private JsoupParseServiceImpl jsoupParseService;

    /**
     * 只提取文本
     *
     * @param weburl
     * @param keywords
     * @return
     */
    @RequestMapping(value = "/abstract", method = RequestMethod.POST)
    public Object getParseInfo(@RequestParam(value = CommonConfig.WEB_URL, required = true) String weburl,
                               @RequestParam(value = CommonConfig.CONTENT_KEY_WORDS, required = true) String keywords) {
        String content = httpUtil.httpGet(weburl);
        String parseResult = jsoupParseService.getTextNewsInfo(content, keywords);
        JSONObject result = new JSONObject();
        content = content.replace("<", "&lt").replace(">", "&gt");
        result.put("html", content);
        result.put("parse", (parseResult == null || parseResult.equals("")) ? "Empty because of something wrong!" : parseResult);
        return result;
    }

    /**
     * 提取带标签的内容
     *
     * @param weburl
     * @param keywords
     * @return
     */
    @RequestMapping(value = "/parse", method = RequestMethod.POST)
    public Object getParseHtmlInfo(@RequestParam(value = CommonConfig.WEB_URL, required = true) String weburl,
                                   @RequestParam(value = CommonConfig.CONTENT_KEY_WORDS, required = true) String keywords) {
        String content = httpUtil.httpGet(weburl);
        String parseResult = jsoupParseService.getHtmlNewsInfo(content, keywords);
        content = content.replace("<", "&lt").replace(">", "&gt");
        JSONObject result = new JSONObject();
        result.put("html", content);
        result.put("parse", (parseResult == null || parseResult.equals("")) ? "Empty because of something wrong!" : parseResult);
        return result;
    }
}
