package com.gupaoedu.mvcframework.dyf.servlet;

import com.gupaoedu.mvcframework.annotation.GPAutowired;
import com.gupaoedu.mvcframework.annotation.GPController;
import com.gupaoedu.mvcframework.annotation.GPRequestMapping;
import com.gupaoedu.mvcframework.annotation.GPService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

public class DyfDispatcherServlet extends HttpServlet {
    //保存加载配置文件的内容
    private Properties contextConfig = new Properties();
    //保存扫描所有的类名
    private List<String> classNames = new ArrayList<String>();
    //传说中的ioc容器
    private Map<String, Object> ioc = new HashMap<String, Object>();
    //保存url和method
    private Map<String, Method> handlerMapping = new HashMap<String, Method>();


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception{
        //绝对路径
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");
        if (!this.handlerMapping.containsKey(url)) {
            resp.getWriter().write("404 Not Found!");
            return;
        }

        Method method = this.handlerMapping.get(url);
        //通过反射拿到method所在的class，拿到class之后还是拿到class的名称
        //再调用toLowerFirstCase获得beanName
        String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());

        Map<String,String[]> params = req.getParameterMap();
        //获取方法的形参列表
        Class<?>[] parameterTypes = method.getParameterTypes();

        Object[] paramsValues = new Object[parameterTypes.length];

        for (int i = 0; i < parameterTypes.length; i++) {
            Class parameterType = parameterTypes[i];
            //不能用instanceof，paramterType它不是实参，而是形参
            if (parameterType == HttpServletRequest.class) {
                paramsValues[i] = req;
                continue;
            }
        }
        method.invoke(ioc.get(beanName), new Object[]{req,resp,params.get("name")[0]});

    }
    //初始化阶段
    @Override
    public void init(ServletConfig config) throws ServletException {
        //1、加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));
        //2、扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));
        //3、初始化扫描的类，并且将它们放入到ioc容器之中
        doInstance();
        //4、完成依赖注入
        doAutowired();
        //5、初始化HanlderMapping
        initHanderMapping();

        System.out.print("DYF Spring frameword is init!");



    }

    private void initHanderMapping() {
        if (ioc.isEmpty()){return;}
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(GPController.class)) {continue;}



            //保存写在上面的@GPRequestMapping("/demo")
            String baseUrl = "";
            if (clazz.isAnnotationPresent(GPRequestMapping.class)) {
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            //默认获取所有的public方法
            for (Method method : clazz.getMethods()) {
                if (method.isAnnotationPresent(GPRequestMapping.class)){continue;}
                GPRequestMapping requestMapping = method.getAnnotation(GPRequestMapping.class);
                String url = ("/" + baseUrl + "/" + requestMapping.value())
                        .replaceAll("/+","/");
                handlerMapping.put(url, method);
                System.out.println("Mapped :"+url+","+method);
            }

        }
    }



    private void doAutowired() {
        if (ioc.isEmpty()){return;}
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            //Declared 所有的，特定的字段，包括private/protected/default
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (!field.isAnnotationPresent(GPAutowired.class)){continue;}
                GPAutowired autowired = field.getAnnotation(GPAutowired.class);
                String beanName = autowired.value().trim();
                if ("".equals(beanName)) {
                    //获得接口的类型，作为key待会拿到这个key到ioc容器中去取值
                    beanName = field.getType().getName();
                }
                field.setAccessible(true);
                try {
                    //使用反射给字段赋值
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void doInstance() {
        //初始化，为DI做准备
        if (classNames.isEmpty()){return;}
        try {
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                //为了简化代码逻辑，主要体会设计思想，只举例 @Controller和@Service,
                if (clazz.isAnnotationPresent(GPController.class)){
                    Object instance = clazz.newInstance();
                    //spring默认类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, instance);
                } else if (clazz.isAnnotationPresent(GPService.class)) {
                    GPService service = clazz.getAnnotation(GPService.class);
                    String beanName = service.value();
                    if ("".equals(beanName.trim())) {
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }
                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);
                    for (Class<?> i : clazz.getInterfaces()) {
                        if (ioc.containsKey(i.getName())) {
                            throw new Exception("The “" + i.getName() + "” is exists!");
                        }
                        //把接口的类型当成key
                        ioc.put(i.getName(), instance);
                    }
                } else {
                    continue;
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private String toLowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray();
        chars[0] += 32;
        return String .valueOf(chars);
    }

    private void doDispatcher(HttpServletRequest req, HttpServletResponse resp){

    }

    private void doScanner(String scanPackage){
        URL url = this.getClass().getClassLoader().getResource("/"+ scanPackage.replaceAll("\\.", "/"));
        File classPath = new File(url.getFile());
        for (File file : classPath.listFiles()) {
            if(file.isDirectory()){
                doScanner(scanPackage+ "."+file.getName());
            }else{
                if(!file.getName().endsWith(".class")){continue;}
                String className = (scanPackage + "." + file.getName().replace(".class", ""));
                classNames.add(className);
            }
        }
    }
    private void doLoadConfig(String contextConfigLocation){
        InputStream fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(fis);
        }catch (IOException e){
            e.printStackTrace();
        }finally {
            if(null != fis){
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }
}
