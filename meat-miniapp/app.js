/**
 * 微信小程序入口 app.js
 */
const auth = require('./utils/auth');
const request = require('./utils/request');

App({
  globalData: {
    userInfo: null,
    isAdmin: false,
    shopInfo: null,
    orderStatusFilter: null
  },

  onLaunch() {
    console.log('小程序启动');

    // 1. 用户主动退出过登录则保持未登录态，不再自动静默登录
    if (auth.isLoggedOut()) {
      console.log('用户已主动退出登录，跳过静默登录');
    } else {
      this.bootstrapLogin();
    }

    // 2. 加载店铺基础配置 (如经纬度、配送距离限制等)
    this.loadShopInfo();
  },

  onShow() {
    // 回到前台校验一次登录态。
    // 不再做定时轮询：后端 JwtInterceptor 会对每个带 Token 的请求校验会话标识，
    // 被其他设备顶下线时接口直接返回 4011，由 request 层统一弹窗提示重新登录。
    this.checkSession();
  },

  /**
   * 启动时恢复登录态：
   * - 本地已有 Token：先向服务端确认它是否仍是「唯一有效会话」。
   *   校验通过则直接复用，不再无条件重登（否则每次冷启动都会刷新会话标识，
   *   造成两台设备互相顶号）；已被顶下线 / 已过期则统一提示重新登录。
   * - 本地没有 Token：走微信静默登录。
   */
  bootstrapLogin() {
    if (auth.getToken()) {
      this.checkSession().then((valid) => {
        if (!valid) return; // 失效场景由 request 层统一提示重新登录
        this.globalData.userInfo = auth.getUserInfo();
        this.globalData.isAdmin = auth.isAdmin();
        console.log('登录态校验通过，当前身份:', auth.getActiveRole());
      });
      return;
    }

    this.checkAndLogin();
  },

  checkAndLogin() {
    auth.login()
      .then((res) => {
        this.globalData.userInfo = res;
        // 身份以本次登录生效身份为准（静默登录不会覆盖掌柜登录态）
        this.globalData.isAdmin = auth.isAdmin();
        console.log('登录成功，当前身份:', auth.getActiveRole());
      })
      .catch((err) => {
        console.warn('静默登录失败，待用户主动交互触发:', err);
      });
  },

  /**
   * 校验当前登录态是否在服务端仍然有效
   * @returns {Promise<Boolean>} 有效返回 true；被顶下线 / 过期返回 false
   */
  checkSession() {
    return request.checkSession();
  },

  loadShopInfo() {
    return request.get('/shop/info', {}, { silent: true })
      .then((res) => {
        this.globalData.shopInfo = res;
        return res;
      })
      .catch((err) => {
        console.warn('获取店铺配置失败:', err);
      });
  }
});
