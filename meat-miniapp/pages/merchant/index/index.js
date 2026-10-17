// pages/merchant/index/index.js
const auth = require('../../../utils/auth');
const request = require('../../../utils/request');
const app = getApp();

Page({
  data: {
    shopInfo: {},
    myShops: [],
    currentShop: {},
    showShopPicker: false
  },

  onShow() {
    // 权限校验拦截
    if (!auth.isAdmin()) {
      wx.showModal({
        title: '权限拦截',
        content: '抱歉，当前账号非肉铺管理员，无法访问掌柜后台。',
        showCancel: false,
        success: () => {
          wx.switchTab({ url: '/pages/mine/mine' });
        }
      });
      return;
    }

    this.loadMyShops();
  },

  loadMyShops() {
    request.get('/shop/admin/my-shops', {}, { silent: true }).then((list) => {
      const myShops = Array.isArray(list) ? list : [];
      let savedShopId = wx.getStorageSync('adminShopId');
      let currentShop = myShops.find(s => s.id === savedShopId);

      if (!currentShop && myShops.length > 0) {
        currentShop = myShops[0];
      }

      if (currentShop) {
        wx.setStorageSync('adminShopId', currentShop.id);
        this.setData({
          myShops,
          currentShop,
          shopInfo: currentShop
        });
      } else {
        // 回退加载常规 shop/info
        this.loadShopInfo();
      }
    }).catch(() => {
      this.loadShopInfo();
    });
  },

  loadShopInfo() {
    request.get('/shop/info', {}, { silent: true }).then((res) => {
      if (res) {
        this.setData({
          shopInfo: res,
          currentShop: res,
          myShops: [res]
        });
      }
    });
  },

  openShopPicker() {
    if (this.data.myShops && this.data.myShops.length > 1) {
      this.setData({ showShopPicker: true });
    }
  },

  closeShopPicker() {
    this.setData({ showShopPicker: false });
  },

  preventBubble() {},

  onSelectAdminShop(e) {
    const shopId = e.currentTarget.dataset.id;
    const shop = this.data.myShops.find(s => s.id === shopId);
    if (shop) {
      this.setData({
        currentShop: shop,
        shopInfo: shop,
        showShopPicker: false
      });
      wx.setStorageSync('adminShopId', shop.id);
      wx.showToast({ title: `已切换管理: ${shop.name}`, icon: 'none' });
    }
  },

  goToOrders(e) {
    const status = e.currentTarget.dataset.status || '';
    const shopId = (this.data.currentShop && this.data.currentShop.id) || '';
    wx.navigateTo({
      url: `/pages/merchant/orders/orders?status=${status}&shopId=${shopId}`
    });
  },

  goToProductList() {
    const shopId = (this.data.currentShop && this.data.currentShop.id) || '';
    wx.navigateTo({
      url: `/pages/merchant/products/list/index?shopId=${shopId}`
    });
  },

  goToCategoryList() {
    wx.navigateTo({
      url: '/pages/merchant/category/index'
    });
  },

  goToShopSetting() {
    const shopId = (this.data.currentShop && this.data.currentShop.id) || '';
    wx.navigateTo({
      url: `/pages/merchant/shop-setting/index?shopId=${shopId}`
    });
  },

  backToCustomer() {
    // 回到顾客端时，默认选中当前掌柜正在管理的门店
    const shop = this.data.currentShop || {};
    if (shop.id) {
      app.globalData.currentShopId = shop.id;
      wx.setStorageSync('currentShopId', shop.id);
    }
    wx.switchTab({
      url: '/pages/index/index'
    });
  },

  logoutAdmin() {
    wx.showModal({
      title: '提示',
      content: '确定退出管理员登录并切回顾客端吗？',
      confirmText: '退出管理',
      cancelText: '取消',
      success: (res) => {
        if (res.confirm) {
          auth.logoutAdmin();
          wx.switchTab({
            url: '/pages/mine/mine'
          });
        }
      }
    });
  }
});
