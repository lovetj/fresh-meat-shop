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
      this.checkAndLogin();
    }

    // 2. 加载店铺基础配置 (如经纬度、配送距离限制等)
    this.loadShopInfo();
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
