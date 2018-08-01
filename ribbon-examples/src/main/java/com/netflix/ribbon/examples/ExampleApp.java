package com.netflix.ribbon.examples;

import com.sun.jersey.api.container.httpserver.HttpServerFactory;
import com.sun.jersey.api.core.PackagesResourceConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Author : yining.gao@msxf.com
 * @Description :
 * @Date : Created in 16:40 2018/7/31
 * @Modify by :
 */
public class ExampleApp extends  ExampleAppWithLocalResource{

    @Override
    public void run() throws Exception {

    }

    public static void main(String[] args) {
        ExampleApp exampleApp = new ExampleApp();
        try {
            exampleApp.runApp();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
