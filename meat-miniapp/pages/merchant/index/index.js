// pages/merchant/index/index.js
const auth = require('../../../utils/auth');
const request = require('../../../utils/request');
const app = getApp();

Page({
  data: {
    shopInfo: {},
    myShops: [],
    currentShop: {},
    shopLoaded: false,
    showShopPicker: false,
    // 阶梯配送规则展示数据
    deliveryTiers: [],
    deliveryCoverText: '',
    hasDeliveryTiers: false
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
        this.applyShopData(currentShop, myShops);
      } else {
        // 关联表中没有任何门店：不再回退默认门店，直接展示空状态
        this.applyEmptyShop();
      }
    }).catch(() => {
      this.applyEmptyShop();
      wx.showToast({ title: '门店列表加载失败', icon: 'none' });
    });
  },

  /**
   * 当前管理员在 shop_admin 中无任何关联门店时的空状态
   */
  applyEmptyShop() {
    wx.removeStorageSync('adminShopId');
    this.setData({
      myShops: [],
      currentShop: {},
      shopInfo: {},
      deliveryTiers: [],
      deliveryCoverText: '',
      hasDeliveryTiers: false,
      showShopPicker: false,
      shopLoaded: true
    });
  },

  /**
   * 应用当前管理门店，并格式化阶梯配送信息
   * @param {Object} shop 当前门店
   * @param {Array} shops 可选门店列表（不传则沿用当前列表）
   */
  applyShopData(shop, shops) {
    const view = this.buildDeliveryTiers(shop);
    const list = shops || this.data.myShops || [];

    // 列表卡片上的配送摘要统一格式化（有阶梯走阶梯，无阶梯回退半径）
    const myShops = list.map((item) => ({
      ...item,
      deliveryText: item.id === shop.id ? view.cardText : this.buildDeliveryTiers(item).cardText
    }));

    this.setData({
      myShops,
      currentShop: { ...shop, deliveryText: view.cardText },
      shopInfo: shop,
      deliveryTiers: view.tiers,
      deliveryCoverText: view.coverText,
      hasDeliveryTiers: view.tiers.length > 0,
      shopLoaded: true
    });
  },

  /**
   * 把阶梯配送规则格式化为展示数据
   * @returns {{tiers: Array, coverText: string, cardText: string}}
   */
  buildDeliveryTiers(shop) {
    const rawRules = (shop && Array.isArray(shop.deliveryRules)) ? shop.deliveryRules.slice() : [];
    const fallbackMax = 5;

    rawRules.sort((a, b) => (Number(a.minDistance) || 0) - (Number(b.minDistance) || 0));

    const tiers = [];
    let hasUnlimited = false;
    let maxDistance = 0;

    rawRules.forEach((rule) => {
      const min = Number(rule.minDistance) || 0;
      const fee = Number(rule.deliveryFee) || 0;
      const minAmount = Number(rule.minOrderAmount) || 0;
      const maxRaw = rule.maxDistance;
      const isUnlimited = maxRaw === null || maxRaw === undefined || maxRaw === '' || Number(maxRaw) <= 0;

      if (isUnlimited) {
        hasUnlimited = true;
      } else if (Number(maxRaw) > maxDistance) {
        maxDistance = Number(maxRaw);
      }

      tiers.push({
        rangeText: isUnlimited ? `${min}km 以上` : `${min}-${Number(maxRaw)}km`,
        desc: minAmount > 0 ? `配送 ¥${fee} · 起送 ¥${minAmount}` : `配送 ¥${fee}`
      });
    });

    const coverText = tiers.length === 0
      ? ''
      : (hasUnlimited ? '不限距离' : `最远 ${maxDistance || fallbackMax}km`);

    const cardText = tiers.length === 0
      ? `配送半径 ${fallbackMax}km`
      : `${tiers.length} 段阶梯 · ${coverText}`;

    return { tiers, coverText, cardText };
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
      this.setData({ showShopPicker: false });
      this.applyShopData(shop);
      wx.setStorageSync('adminShopId', shop.id);
      wx.showToast({ title: `已切换管理: ${shop.name}`, icon: 'none' });
    }
  },

  /**
   * 取当前门店 id，无关联门店时提示并阻止跳转
   */
  requireCurrentShop() {
    const shopId = (this.data.currentShop && this.data.currentShop.id) || '';
    if (!shopId) {
      wx.showToast({ title: '当前账号未关联门店', icon: 'none' });
    }
    return shopId;
  },

  goToOrders(e) {
    const shopId = this.requireCurrentShop();
    if (!shopId) return;
    const status = e.currentTarget.dataset.status || '';
    wx.navigateTo({
      url: `/pages/merchant/orders/orders?status=${status}&shopId=${shopId}`
    });
  },

  goToProductList() {
    const shopId = this.requireCurrentShop();
    if (!shopId) return;
    wx.navigateTo({
      url: `/pages/merchant/products/list/index?shopId=${shopId}`
    });
  },

  goToCategoryList() {
    const shopId = this.requireCurrentShop();
    if (!shopId) return;
    wx.navigateTo({
      url: '/pages/merchant/category/index'
    });
  },

  goToShopSetting() {
    const shopId = this.requireCurrentShop();
    if (!shopId) return;
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
      // 掌柜回到顾客端属于明确选店，同步为用户历史选择门店
      wx.setStorageSync('selectedShopId', shop.id);
    }
    wx.switchTab({
      url: '/pages/index/index'
    });
  }
});
