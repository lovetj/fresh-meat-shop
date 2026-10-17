# 维琼鲜肉铺 - 同城肉铺下单配送系统

「维琼鲜肉铺」是一个专为同城实体生鲜肉铺量身打造的微信小程序下单与配送管理系统。
系统采用 **Spring Boot + MyBatis-Plus + MySQL** 作为后端服务，结合 **微信小程序原生框架** 实现顾客端与掌柜管理端（同在一个小程序内，基于用户 openid 及角色自动区分）。

---

## 一、核心业务特性与规则实现

1. **同城配送，直接下单**：
   - 砍去冗余购物车链路，选中心仪鲜肉（可选定制切法如薄片/肉馅/排骨段）直接进入结算页下单。
2. **配送方式三选一**：
   - **到店自取**：到店报手机号提货核销，无需配送费；
   - **商家配送**：由肉铺专人送货上门，自动计算运费并校验距离；
   - **外卖平台**：保留美团/饿了么外卖入口（暂不支持下单，弹窗引导）。
3. **高精度球面距离校验 (Haversine 算法)**：
   - 店铺可自由设置最大配送半径（如 5.0 km）。
   - 顾客在结算页选择收货地址时，基于经纬度实时测算直线距离。若超出最大配送半径，系统将拦截提交，并引导顾客切换为“到店自取”或更换地址。
4. **灵活支付方式 (货到付款 + 微信支付 V3)**：
   - **货到付款**：下单直接进入“待接单”状态，送达或自提时线下收取现金/面对面扫码；
   - **微信支付**：保留完整的微信支付 V3 统一下单、商户私钥签名、回调验签与 AES-256-GCM 解密代码。内置 `mock: true` 开关，在开发/测试阶段无须真实商户号即可调起模拟支付，完成全流程状态闭环。
5. **双端一体化（角色无缝切换）**：
   - 普通顾客进入：浏览选肉、挑选切工规格、提交订单、跟踪状态、管理地址；
   - 掌柜管理员进入（根据微信 `openid` 绑定管理员身份）：在「我的」页面展示「掌柜管理控制台」，提供一键接单、拒单退款、通知备齐/开始配送、送达核销、商品与切法管理、库存修改、分类管理、门店坐标与配送半径设置。

---

## 二、系统架构与目录结构

```text
fresh-meat-shop/
├── meat-backend/                        # 后端 Spring Boot 工程
│   ├── pom.xml                          # Maven 依赖
│   ├── sql/
│   │   └── schema.sql                   # MySQL 8 建表脚本与初始数据
│   └── src/main/
│       ├── java/com/freshmeat/
│       │   ├── MeatApplication.java      # 启动主类
│       │   ├── annotation/              # @RequireAdmin 注解
│       │   ├── common/                  # Result, PageResult 统一响应结构
│       │   ├── config/                  # WebMvc, 跨域, 静态资源映射
│       │   ├── controller/              # 控制器 (User, Shop, Product, Order, Payment...)
│       │   ├── dto/                     # 入参 DTO 与出参 VO
│       │   ├── entity/                  # 实体类 (User, Shop, Product, Order, Item, Pay...)
│       │   ├── enums/                   # 订单状态、配送方式、支付类型枚举
│       │   ├── exception/               # 业务异常与全局异常处理器
│       │   ├── interceptor/             # JWT 鉴权与管理员权限拦截器
│       │   ├── mapper/                  # MyBatis-Plus 数据访问层
│       │   ├── service/                 # 业务逻辑接口及实现
│       │   └── util/                    # Haversine距离计算、微信支付V3、JWT工具类
│       └── resources/
│           └── application.yml          # 数据源、JWT、微信与商户号配置
│
└── meat-miniapp/                        # 原生微信小程序前端
    ├── app.json                         # 全局配置、路由、tabBar、位置权限
    ├── app.js                           # 小程序入口、登录态检查
    ├── app.wxss                         # 全局鲜肉红主题样式
    ├── utils/
    │   ├── config.js                    # 后端 API 地址配置与图片 URL 转换
    │   ├── request.js                   # wx.request 封装、Token 注入、上传与异常处理
    │   ├── auth.js                      # Token 存储、用户角色判断、快捷模拟登录
    │   └── distance.js                  # 前端 Haversine 距离快速预估
    └── pages/
        ├── index/                       # 鲜肉选购首页 (分类切换、商品瀑布、切法弹窗)
        ├── product-detail/              # 鲜肉商品详情页
        ├── checkout/                    # 立即下单结算页 (配送方式/测距拦截/支付方式)
        ├── orders/                      # 顾客订单列表页 (按状态筛选)
        ├── order-detail/                # 订单详情 (明细、取消、微信支付、收货)
        ├── address/                     # 收货地址列表与地图选点编辑
        ├── mine/                        # 个人中心 (角色入口、联系掌柜)
        └── merchant/                    # 掌柜管理员专属模块
            ├── index/                   # 掌柜控制台工作台
            ├── orders/                  # 订单管理 (接单、拒单退款、发货自提、核销)
            ├── products/list/           # 商品列表 (搜索、上下架、改库存、删除)
            ├── products/edit/           # 商品编辑/新增 (主图上传、切法规格标签配置)
            ├── category/                # 分类管理 (增删改、排序与启用)
            └── shop-setting/            # 店铺与配送设置 (地图选点坐标、最大配送距离)
```

---

## 三、订单状态机流转说明

| 状态码 | 状态名称 | 触发条件 / 操作 |
| :---: | :---: | :--- |
| `0` | **待支付** | 选择了【线上微信支付】并提交订单，等待顾客支付。顾客可在详情页取消订单。 |
| `1` | **待接单** | ① 微信支付成功（微信回调 / Mock 支付完成）；<br>② 选择【货到付款】直接进入该状态。<br>掌柜可在此状态点击**「立即接单」**或**「拒单」**（拒单自动原路退款并回退库存）。 |
| `2` | **待配送 / 待自取** | 掌柜接单后流转至此状态：<br>- 商家配送订单：掌柜切配打包后点击**「开始同城配送」**流转为 `3`；<br>- 到店自取订单：掌柜切好后点击**「通知备齐可自提」**，顾客到店后掌柜点击**「到店提货核销」**直接流转为 `4`。 |
| `3` | **配送中** | 配送人员在送途中，顾客或掌柜点击**「确认送达完成」**后流转为 `4`。 |
| `4` | **已完成** | 交易圆满完成。 |
| `5` | **已取消** | 顾客在未接单前主动取消订单，若已支付则触发原路退款并回滚库存。 |
| `6` | **已拒单** | 掌柜因鲜肉售罄等原因拒单，若已支付则触发原路退款并回滚库存。 |

---

## 四、启动与部署步骤

### 1. 数据库准备与初始化
1. 本地安装 **MySQL 8.0+**，确保服务已开启；
2. 登录 MySQL 执行建表脚本：
   ```bash
   mysql -u root -p < d:/weChatProject/fresh-meat-shop/meat-backend/sql/schema.sql
   ```
   *该脚本会自动创建 `fresh_meat_shop` 数据库，创建 8 张核心表并预置测试商品、分类、店铺配置及管理员账号（openid 为 `admin_mock_openid`）。*

### 2. 后端配置与启动 (Spring Boot)
1. 打开 `meat-backend/src/main/resources/application.yml`：
   - 检查并修改 MySQL 用户名与密码：
     ```yaml
     spring:
       datasource:
         url: jdbc:mysql://localhost:3306/fresh_meat_shop?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false
         username: root
         password: your_password
     ```
   - 微信小程序与微信支付配置：
     ```yaml
     wechat:
       miniapp:
         appid: your_wx_appid          # 微信小程序 AppID
         secret: your_wx_secret        # 微信小程序 AppSecret
       pay:
         mch-id: your_mch_id           # 微信支付商户号
         api-v3-key: your_api_v3_key   # APIv3 密钥 (32字节)
         serial-no: your_serial_no     # 商户证书序列号
         private-key-path: classpath:apiclient_key.pem  # 商户私钥路径
         notify-url: https://yourdomain.com/api/payment/notify
         mock: true                    # true: 本地开发测试Mock支付模式; false: 调起真实微信支付
     ```
2. 启动后端项目：
   - 使用 Maven 命令行：
     ```bash
     cd d:\weChatProject\fresh-meat-shop\meat-backend
     mvn spring-boot:run
     ```
   - 或使用 IDEA / VS Code 直接运行 `com.freshmeat.MeatApplication`。
   - 启动成功后，后端接口默认运行在 `http://localhost:8080/meat`。
   - **Swagger / Knife4j 在线接口文档地址**：
     - Knife4j 增强文档页：`http://localhost:8080/meat/doc.html`
     - 原生 Swagger-UI 页：`http://localhost:8080/meat/swagger-ui/index.html`
     - *（支持在页面右上角或者全局请求头输入 `Bearer <token>` 直接调试需鉴权的接口）*

### 3. 前端导入与调试 (微信开发者工具)
1. 下载并打开 **微信开发者工具**；
2. 点击 **导入项目**：
   - 项目目录选择：`d:\weChatProject\fresh-meat-shop\meat-miniapp`；
   - AppID：填入自己的测试 AppID 或点击“测试号”；
3. 开发环境配置：
   - 在微信开发者工具右上角点击 **详情** -> **本地设置**，勾选 **「不校验合法域名、web-view（业务域名）、TLS版本以及HTTPS证书」**；
4. 检查前端配置：
   - 打开 `meat-miniapp/utils/config.js`，确认 `BASE_URL` 指向当前后端地址（默认为 `http://localhost:8080/api`）；
   - 在真机预览时，请将 `localhost` 改为本机局域网 IP（如 `http://192.168.1.xxx:8080/api`）。

---

## 五、管理员端角色切换与联调测试指引

系统支持双重角色鉴权机制：
1. **微信正式模式**：在微信公众平台后台将肉铺掌柜微信号的 openid 记录在数据库 `sys_user` 表中，并将 `role` 字段设置为 `ADMIN`；当该用户微信登录时，系统自动识别为管理员。
2. **开发测试极速模式**：
   - 打开小程序底部的「我的」页面；
   - 找到测试工具栏中的 **「切换身份」** 按钮：
     - 点击 **「切为掌柜」**：即可立即切换为内置掌柜管理员（OpenID: `admin_mock_openid`），个人中心将自动展示「掌柜后台入口」，点击即可进入管理控制台处理订单、增删改商品和修改配送距离；
     - 点击 **「切为顾客」**：可切换回普通买肉顾客身份，验证下单、距离超距拦截提示与付款流转。
