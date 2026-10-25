// pages/product-detail/product-detail.js
const app = getApp();
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const cart = require('../../utils/cart');
const { normalizeShopStatus, isOrderBlocked } = require('../../utils/shop-status');
const { formatImageUrl, normalizeSpecsList } = require('../../utils/util');

Page({
  data: {
    productId: null,
    product: null,
    specsList: [], // 加工方式列表: [{ name: '切片', options: ['薄片', '厚片'] }]
    selectedMethodIndex: -1,
    selectedMethod: '',
    currentOptions: [],
    selectedOption: '',
    buyQuantity: 1,
    totalPrice: '0.00',

    // 购物车与悬浮球状态
    cartCount: 0,
    cartTotalAmount: '0.00',
    cartList: [],
    showCartDrawer: false,

    // 门店是否歇业（歇业中禁止加购与下单）
    shopClosed: false,
    shopStatusTip: ''
  },

  onLoad(options) {
    if (options.id) {
      this.setData({ productId: options.id });
      this.loadDetail(options.id);
    }
    this.refreshShopStatus();
  },

  onShow() {
    this.refreshCart();
    this.refreshShopStatus();
  },

  /** 刷新门店营业状态：歇业中禁用加购与下单入口 */
  refreshShopStatus() {
    const shopId = (this.data.product && this.data.product.shopId)
      || app.globalData.currentShopId
      || '';
    const params = shopId ? { shopId } : {};
    request.get('/shop/info', params, { silent: true }).then((res) => {
      if (!res) return;
      const shop = normalizeShopStatus(res);
      app.globalData.shopInfo = shop;
      this.setData({
        shopClosed: shop.displayStatus === 3,
        shopStatusTip: shop.statusTip || ''
      });
    }).catch(() => {});
  },

  /** 歇业提示 */
  tipShopClosed() {
    wx.showModal({
      title: '门店已歇业',
      content: this.data.shopStatusTip || '该门店当前处于歇业状态，暂不接单。可返回首页切换其他门店或稍后再来～',
      showCancel: false,
      confirmText: '我知道了'
    });
  },

  loadDetail(id) {
    wx.showLoading({ title: '加载中...' });
    request.get(`/product/detail/${id}`).then((res) => {
      wx.hideLoading();
      const specsList = normalizeSpecsList(res.specsJson);

      // 默认选中第一个加工方式及其首个具体规格
      let selectedMethodIndex = -1;
      let selectedMethod = '';
      let currentOptions = [];
      let selectedOption = '';

      if (specsList.length > 0) {
        selectedMethodIndex = 0;
        selectedMethod = specsList[0].name;
        currentOptions = specsList[0].options || [];
        selectedOption = currentOptions.length > 0 ? currentOptions[0] : '';
      }

      const totalPrice = (Number(res.price) * this.data.buyQuantity).toFixed(2);

      this.setData({
        product: {
          ...res,
          coverImage: formatImageUrl(res.coverImage)
        },
        specsList,
        selectedMethodIndex,
        selectedMethod,
        currentOptions,
        selectedOption,
        totalPrice
      }, () => {
        this.refreshCart();
      });
    }).catch(() => {
      wx.hideLoading();
    });
  },

  // 刷新当前门店购物车缓存状态
  refreshCart() {
    const shopId = (this.data.product && this.data.product.shopId) || app.globalData.currentShopId || '';
    if (!shopId) {
      // 无门店时也要复位，避免退出登录/切换身份后角标仍保留旧数量
      this.setData({ cartCount: 0, cartTotalAmount: '0.00', cartList: [] });
      return;
    }

    const summary = cart.getCartSummary(shopId);
    this.setData({
      cartCount: summary.totalCount,
      cartTotalAmount: summary.totalAmount,
      cartList: summary.cartList
    });
  },

  // 选择加工方式：联动刷新下方可用的具体规格
  onSelectMethod(e) {
    const index = Number(e.currentTarget.dataset.index);
    const methodItem = this.data.specsList[index];
    if (!methodItem) return;

    const options = methodItem.options || [];
    this.setData({
      selectedMethodIndex: index,
      selectedMethod: methodItem.name,
      currentOptions: options,
      selectedOption: options.length > 0 ? options[0] : ''
    });
  },

  // 必须选了加工方式之后，选择该加工方式下的具体规格
  onSelectOption(e) {
    if (this.data.selectedMethodIndex < 0 || !this.data.selectedMethod) {
      wx.showToast({ title: '请先选择加工方式', icon: 'none' });
      return;
    }
    const { opt } = e.currentTarget.dataset;
    this.setData({ selectedOption: opt });
  },

  onDecQuantity() {
    if (this.data.buyQuantity <= 1) return;
    const buyQuantity = this.data.buyQuantity - 1;
    const totalPrice = (Number(this.data.product.price) * buyQuantity).toFixed(2);
    this.setData({ buyQuantity, totalPrice });
  },

  onIncQuantity() {
    if (this.data.buyQuantity >= this.data.product.stock) {
      wx.showToast({ title: '库存不足', icon: 'none' });
      return;
    }
    const buyQuantity = this.data.buyQuantity + 1;
    const totalPrice = (Number(this.data.product.price) * buyQuantity).toFixed(2);
    this.setData({ buyQuantity, totalPrice });
  },

  callShop() {
    const shop = app.globalData.shopInfo;
    const phone = (shop && shop.phone) || '13800138000';
    wx.makePhoneCall({ phoneNumber: phone });
  },

  // 加入购物车（详情页中不下单）
  onAddToCart() {
    if (!auth.requireLogin({ content: '登录后才能将商品加入购物车' })) return;

    // 歇业中相当于关门：不允许加购
    if (this.data.shopClosed) {
      this.tipShopClosed();
      return;
    }

    const { product, buyQuantity, specsList, selectedMethod, selectedOption } = this.data;
    if (!product) return;

    let specInfo = (product && product.unit) || '';
    if (specsList && specsList.length > 0) {
      if (!selectedMethod) {
        wx.showToast({ title: '请选择加工方式', icon: 'none' });
        return;
      }
      if (!selectedOption) {
        wx.showToast({ title: '请选择具体加工规格', icon: 'none' });
        return;
      }
      specInfo = selectedMethod === selectedOption ? selectedMethod : `${selectedMethod} · ${selectedOption}`;
    }

    const shopId = product.shopId || app.globalData.currentShopId || '';
    const res = cart.addToCart(shopId, product, specInfo, buyQuantity);

    if (res.success) {
      this.refreshCart();
      wx.showToast({
        title: '已加入购物车',
        icon: 'success'
      });
    } else {
      wx.showToast({
        title: res.message || '加购失败',
        icon: 'none'
      });
    }
  },

  // 打开购物车展开抽屉
  openCartDrawer() {
    this.refreshCart();
    this.setData({ showCartDrawer: true });
  },

  // 关闭购物车展开抽屉
  closeCartDrawer() {
    this.setData({ showCartDrawer: false });
  },

  preventBubble() {},

  // 购物车内商品增加数量
  onCartInc(e) {
    const id = e.currentTarget.dataset.id;
    const shopId = (this.data.product && this.data.product.shopId) || app.globalData.currentShopId || '';
    cart.changeQuantity(shopId, id, 1);
    this.refreshCart();
  },

  // 购物车内商品减少数量
  onCartDec(e) {
    const id = e.currentTarget.dataset.id;
    const shopId = (this.data.product && this.data.product.shopId) || app.globalData.currentShopId || '';
    cart.changeQuantity(shopId, id, -1);
    this.refreshCart();
  },

  // 清空当前门店购物车
  onClearCart() {
    const shopId = (this.data.product && this.data.product.shopId) || app.globalData.currentShopId || '';
    wx.showModal({
      title: '清空小推车',
      content: '确定要清空购物车里的所有肉肉吗？',
      confirmColor: '#FF6B6B',
      success: (res) => {
        if (res.confirm) {
          cart.clearCart(shopId);
          this.refreshCart();
          wx.showToast({ title: '已清空购物车', icon: 'none' });
        }
      }
    });
  },

  // 在悬浮球展开抽屉中下单 (去结算)
  goToCartCheckout() {
    if (this.data.cartCount <= 0) {
      wx.showToast({ title: '购物车还是空的哦～', icon: 'none' });
      return;
    }

    // 歇业中相当于关门，直接拦截下单入口
    if (this.data.shopClosed || isOrderBlocked(app.globalData.shopInfo)) {
      this.tipShopClosed();
      return;
    }

    const shopId = (this.data.product && this.data.product.shopId) || app.globalData.currentShopId || '';
    this.closeCartDrawer();
    wx.navigateTo({
      url: `/pages/checkout/checkout?from=cart&shopId=${shopId}`
    });
  }
});
