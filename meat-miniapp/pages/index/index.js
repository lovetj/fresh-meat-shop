// pages/index/index.js
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const cart = require('../../utils/cart');
const { formatImageUrl, normalizeSpecsList } = require('../../utils/util');
const app = getApp();

// 「全部鲜肉」的虚拟分类ID：后端分类ID为雪花字符串，0 仅用于前端占位，不会下发给接口
const ALL_CATEGORY_ID = '0';

Page({
  data: {
    shopInfo: {},
    shopList: [],
    currentShopId: '',
    showShopModal: false,

    // 门店选择弹窗：搜索 + 分页滚动加载
    shopKeyword: '',
    shopPageNum: 1,
    shopPageSize: 10,
    shopTotal: 0,
    shopHasMore: true,
    shopLoading: false,
    shopLoadingMore: false,
    // 弹层打开时给 page 加 overflow:hidden，锁定底层页面滚动
    pageStyle: '',
    categories: [],
    currentCategoryId: ALL_CATEGORY_ID,
    keyword: '',
    goodsList: [],
    pageNum: 1,
    pageSize: 4,
    hasMore: true,
    loading: false,
    loadingMore: false,

    // 选购规格弹窗
    showSpecModal: false,
    currentProduct: {},
    specGroups: [],
    selectedSpecs: [],
    selectedSpecText: '',
    buyQuantity: 1,
    totalPrice: '0.00',

    // 购物车与悬浮球状态
    cartCount: 0,
    cartTotalAmount: '0.00',
    cartList: [],
    showCartDrawer: false
  },

  /**
   * 页面数据统一在 onShow 中刷新：
   * 首次进入、以及每次从其他页签切回「鲜肉选购」时，都会重新请求本页涉及的全部接口
   * （onLoad 不再重复请求，避免首屏两次调用）
   */
  onShow() {
    this.refreshPageData();
    this.refreshCart();

    // 同步自定义 tabBar 选中态（任一半屏弹层打开时保持隐藏）
    const tabBar = this.getTabBar ? this.getTabBar() : null;
    if (tabBar) {
      tabBar.setData({
        selected: 0,
        hidden: !!(this.data.showShopModal || this.data.showSpecModal || this.data.showCartDrawer)
      });
    }
  },

  /** 隐藏/显示底部自定义 tabBar（全屏浮层打开时隐藏，避免遮挡） */
  setTabBarHidden(hidden) {
    const tabBar = this.getTabBar ? this.getTabBar() : null;
    if (tabBar) {
      tabBar.setData({ hidden: !!hidden });
    }
  },

  /**
   * 刷新本页涉及的全部接口：店铺列表与信息 / 分类列表 / 商品首屏
   * @returns {Promise} 全部请求结束（单个失败不阻断其它接口）
   */
  async refreshPageData() {
    await this.loadShopInfo();
    return Promise.all([
      this.loadCategories(),
      this.loadGoods(true)
    ]);
  },

  // 页面滚动到底部（触底追加分页）
  onReachBottom() {
    console.log('[鲜肉选购] 滚动触底，触发追加分页: hasMore =', this.data.hasMore, 'loading =', this.data.loading);
    this.onLoadMore();
  },

  // 顶部下拉刷新
  onPullDownRefresh() {
    this.refreshPageData().finally(() => {
      wx.stopPullDownRefresh();
    });
  },

  /**
   * 加载门店首屏并解析当前生效商铺
   */
  loadShopInfo() {
    return new Promise((resolve) => {
      // 尝试获取当前用户经纬度辅助测距排序
      const load = (coords) => {
        this._coords = coords || null;
        this.loadShopPage(true)
          .then((page) => this.resolveCurrentShop(page.list))
          .then(resolve)
          .catch((err) => {
            console.error('[鲜肉选购] 加载门店列表失败，回退单店:', err);
            request.get('/shop/info', {}, { silent: true }).then((shop) => {
              if (shop) {
                this.applyCurrentShop(shop);
              }
              resolve(shop || null);
            }).catch(() => resolve(null));
          });
      };

      if (wx.getLocation) {
        wx.getLocation({
          type: 'gcj02',
          success: (res) => {
            load({ latitude: res.latitude, longitude: res.longitude });
          },
          fail: () => {
            load();
          }
        });
      } else {
        load();
      }
    });
  },

  /**
   * 将阶梯配送规则解析为便于展示的字段
   */
  formatShopDeliveryInfo(shop) {
    if (!shop) return null;
    const rules = Array.isArray(shop.deliveryRules) ? shop.deliveryRules : [];
    let minOrder = 0;
    let minFee = 0;
    let maxDistance = 0;
    let hasUnlimited = false;
    if (rules.length > 0) {
      minOrder = rules[0].minOrderAmount !== undefined ? rules[0].minOrderAmount : 0;
      minFee = rules[0].deliveryFee !== undefined ? rules[0].deliveryFee : 0;
      rules.forEach(r => {
        if (r.maxDistance === null || r.maxDistance === undefined || Number(r.maxDistance) <= 0) {
          hasUnlimited = true;
        } else if (Number(r.maxDistance) > maxDistance) {
          maxDistance = Number(r.maxDistance);
        }
      });
    }
    const deliveryRangeText = rules.length === 0 
      ? '同城配送' 
      : (hasUnlimited ? '不限距离' : `${maxDistance || 5}km 内`);
    return {
      ...shop,
      minOrderAmountText: minOrder,
      deliveryFeeText: minFee,
      deliveryRangeText: deliveryRangeText
    };
  },

  /** 写入当前生效门店（页面、globalData、缓存三处同步） */
  applyCurrentShop(shop) {
    if (!shop) return null;
    const formatted = this.formatShopDeliveryInfo(shop);
    this.setData({ currentShopId: formatted.id, shopInfo: formatted }, () => {
      this.refreshCart();
    });
    app.globalData.shopInfo = formatted;
    app.globalData.currentShopId = formatted.id;
    wx.setStorageSync('currentShopId', formatted.id);
    return formatted;
  },

  /**
   * 还原当前生效门店：
   * 1. 用户明确选过门店（selectedShopId）→ 沿用历史选择；
   * 2. 否则取列表首项，即后端按「营业中优先 + 距离由近到远」排序后的最近门店。
   */
  resolveCurrentShop(list) {
    const shops = Array.isArray(list) ? list : [];
    // 仅「用户主动选店」或「掌柜切回前台指定门店」会写入 selectedShopId
    const storedShopId = wx.getStorageSync('selectedShopId') || '';
    const matched = storedShopId ? shops.find((s) => s.id === storedShopId) : null;

    if (matched) {
      return Promise.resolve(this.applyCurrentShop(matched));
    }

    if (storedShopId) {
      return request.get('/shop/info', { shopId: storedShopId }, { silent: true })
        .then((shop) => {
          if (shop) {
            return this.applyCurrentShop(shop);
          }
          // 历史门店已不存在（被删除/下线），清除记录后回退最近门店
          wx.removeStorageSync('selectedShopId');
          return this.pickNearestShop(shops);
        })
        .catch(() => this.pickNearestShop(shops));
    }

    return Promise.resolve(this.pickNearestShop(shops));
  },

  /** 未选过门店时，默认使用列表首个门店（已按距离由近到远排序） */
  pickNearestShop(shops) {
    if (Array.isArray(shops) && shops.length > 0) {
      return this.applyCurrentShop(shops[0]);
    }
    return null;
  },

  /**
   * 分页加载门店列表
   * @param {boolean} reset 是否重置为第 1 页（首次加载 / 搜索关键词变化）
   */
  loadShopPage(reset = false) {
    if (!reset && (this.data.shopLoadingMore || !this.data.shopHasMore)) {
      return Promise.resolve({ list: this.data.shopList, total: this.data.shopTotal });
    }

    // 请求序号：关键词快速变更时丢弃过期响应
    const seq = reset ? (this._shopReqSeq = (this._shopReqSeq || 0) + 1) : (this._shopReqSeq || 0);
    const pageNum = reset ? 1 : this.data.shopPageNum;

    this.setData({ shopLoading: reset, shopLoadingMore: !reset });

    const params = {
      pageNum,
      pageSize: this.data.shopPageSize
    };
    const keyword = (this.data.shopKeyword || '').trim();
    if (keyword) {
      params.keyword = keyword;
    }
    if (this._coords && this._coords.latitude && this._coords.longitude) {
      params.latitude = this._coords.latitude;
      params.longitude = this._coords.longitude;
    }

    return request.get('/shop/page', params, { silent: true }).then((res) => {
      if (seq !== this._shopReqSeq) {
        return { list: this.data.shopList, total: this.data.shopTotal };
      }

      const rawList = (res && (res.list || res.records)) || [];
      const formattedList = rawList.map(item => this.formatShopDeliveryInfo(item));
      const total = res && res.total !== undefined ? Number(res.total) : rawList.length;
      const list = reset ? formattedList : this.data.shopList.concat(formattedList);

      this.setData({
        shopList: list,
        shopTotal: total,
        shopPageNum: pageNum + 1,
        shopHasMore: list.length < total,
        shopLoading: false,
        shopLoadingMore: false
      });

      return { list, total };
    }).catch((err) => {
      console.error('[鲜肉选购] 加载门店分页失败:', err);
      this.setData({ shopLoading: false, shopLoadingMore: false });
      throw err;
    });
  },

  /**
   * 打开门店弹窗：先播放入场动画，动画结束后再请求第 1 页，
   * 避免「弹窗渲染 + 接口返回」同时发生导致的卡顿
   */
  openShopModal() {
    this.setTabBarHidden(true);
    this.setData({ showShopModal: true, shopLoading: true, pageStyle: 'overflow: hidden;' });

    if (this._shopOpenTimer) {
      clearTimeout(this._shopOpenTimer);
    }
    this._shopOpenTimer = setTimeout(() => {
      this._shopOpenTimer = null;
      this.setData({
        shopPageNum: 1,
        shopTotal: 0,
        shopHasMore: true,
        shopLoadingMore: false
      });
      this.loadShopPage(true).catch(() => {});
    }, 280);
  },

  closeShopModal() {
    if (this._shopSearchTimer) {
      clearTimeout(this._shopSearchTimer);
      this._shopSearchTimer = null;
    }
    if (this._shopOpenTimer) {
      clearTimeout(this._shopOpenTimer);
      this._shopOpenTimer = null;
    }
    // 关闭时清空搜索词，避免影响 onShow 时首页门店列表的刷新
    this.setData({ showShopModal: false, shopKeyword: '', shopLoading: false, shopLoadingMore: false, pageStyle: '' });
    this.setTabBarHidden(false);
  },

  /** 门店搜索（300ms 防抖，按名称/地址模糊匹配） */
  onShopKeywordInput(e) {
    this.setData({ shopKeyword: e.detail.value });
    if (this._shopSearchTimer) {
      clearTimeout(this._shopSearchTimer);
    }
    this._shopSearchTimer = setTimeout(() => {
      this.loadShopPage(true).catch(() => {});
    }, 300);
  },

  onShopSearch() {
    if (this._shopSearchTimer) {
      clearTimeout(this._shopSearchTimer);
      this._shopSearchTimer = null;
    }
    this.loadShopPage(true).catch(() => {});
  },

  onShopClearKeyword() {
    if (this._shopSearchTimer) {
      clearTimeout(this._shopSearchTimer);
      this._shopSearchTimer = null;
    }
    this.setData({ shopKeyword: '' }, () => {
      this.loadShopPage(true).catch(() => {});
    });
  },

  /** 门店列表滚动到底部：加载下一页 */
  onShopListLower() {
    this.loadShopPage(false).catch(() => {});
  },

  onSelectShop(e) {
    const shopId = e.currentTarget.dataset.id;
    if (shopId === this.data.currentShopId) {
      this.closeShopModal();
      return;
    }

    const selectedShop = (this.data.shopList || []).find(s => s.id === shopId);
    if (selectedShop) {
      this.applyCurrentShop(selectedShop);
      // 记录用户主动选择，后续进入首页优先展示该门店
      wx.setStorageSync('selectedShopId', selectedShop.id);
      this.closeShopModal();
      wx.showToast({ title: `已切换至: ${selectedShop.name}`, icon: 'none' });
      // 切换门店后重置选中分类，并重新刷新该门店专属的分类和商品
      this.setData({ currentCategoryId: ALL_CATEGORY_ID });
      Promise.all([
        this.loadCategories(),
        this.loadGoods(true)
      ]);
    } else {
      this.closeShopModal();
    }
  },

  loadCategories() {
    const params = {};
    if (this.data.currentShopId) {
      params.shopId = this.data.currentShopId;
    }
    console.log('[鲜肉选购] 开始请求门店分类列表: /category/list', params);
    return request.get('/category/list', params, { silent: true }).then((res) => {
      console.log('[鲜肉选购] 分类列表返回结果:', res);
      const rawList = Array.isArray(res) ? res : (res && Array.isArray(res.data) ? res.data : []);
      // 分类ID统一为字符串，避免与 currentCategoryId 做 === 比较时类型不一致
      const categories = rawList.map((item) => ({ ...item, id: String(item.id) }));
      this.setData({ categories });
    }).catch((err) => {
      console.error('[鲜肉选购] 加载分类列表异常:', err);
    });
  },

  loadGoods(reset = false) {
    // 追加分页时避免重复请求；重置请求（页签切回/分类切换）始终发起
    if (!reset && this.data.loading) return Promise.resolve();

    // 请求序号：快速切换页签/分类时，丢弃已过期的响应
    const seq = (this._goodsReqSeq = (this._goodsReqSeq || 0) + 1);
    const pageNum = reset ? 1 : this.data.pageNum;
    // 仅追加分页展示底部加载动画，重置首屏不展示（首屏用骨架屏）
    this.setData({ loading: true, loadingMore: !reset });

    const params = {
      pageNum,
      pageSize: this.data.pageSize
    };
    if (this.data.currentShopId) {
      params.shopId = this.data.currentShopId;
    }
    if (this.data.currentCategoryId && this.data.currentCategoryId !== ALL_CATEGORY_ID) {
      params.categoryId = this.data.currentCategoryId;
    }
    if (this.data.keyword && this.data.keyword.trim()) {
      params.keyword = this.data.keyword.trim();
    }

    console.log('[鲜肉选购] 发起商品请求:', params, reset ? '(重置首屏)' : '(追加分页)');

    return request.get('/product/list', params).then((res) => {
      // 已有更新的请求在途，忽略本次过期数据
      if (seq !== this._goodsReqSeq) return;
      console.log('[鲜肉选购] 商品列表返回结果:', res);
      const rawList = res.list || res.records || [];
      const list = rawList.map((item) => ({
        ...item,
        coverImage: formatImageUrl(item.coverImage)
      }));
      const total = res.total !== undefined ? res.total : rawList.length;
      const currentList = reset ? list : this.data.goodsList.concat(list);
      const hasMore = currentList.length < total;

      this.setData({
        goodsList: currentList,
        pageNum: pageNum + 1,
        hasMore,
        loading: false,
        loadingMore: false
      });
    }).catch((err) => {
      if (seq !== this._goodsReqSeq) return;
      console.error('[鲜肉选购] 加载商品列表异常:', err);
      this.setData({ loading: false, loadingMore: false });
    });
  },

  onSelectCategory(e) {
    const rawId = e.currentTarget.dataset.id;
    // dataset 取值可能是字符串或数字，统一规范为字符串（「全部鲜肉」为 '0'）
    const isAll = rawId === undefined || rawId === null || rawId === '' || rawId === 0 || rawId === '0';
    const id = isAll ? ALL_CATEGORY_ID : String(rawId);
    if (String(this.data.currentCategoryId) === id) return;
    // 先清空旧列表并进入骨架屏，避免展示上一个分类的商品
    this.setData({
      currentCategoryId: id,
      pageNum: 1,
      hasMore: true,
      goodsList: [],
      loading: true
    }, () => {
      this.loadGoods(true);
    });
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value });
  },

  onSearch() {
    this.loadGoods(true);
  },

  onClearKeyword() {
    this.setData({ keyword: '' }, () => {
      this.loadGoods(true);
    });
  },

  onLoadMore() {
    if (this.data.hasMore && !this.data.loading) {
      this.loadGoods(false);
    }
  },

  goToDetail(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: `/pages/product-detail/product-detail?id=${id}`
    });
  },

  openShopLocation() {
    const shop = this.data.shopInfo;
    if (!shop.latitude || !shop.longitude) {
      wx.showToast({ title: '肉铺未配置坐标', icon: 'none' });
      return;
    }
    wx.openLocation({
      latitude: Number(shop.latitude),
      longitude: Number(shop.longitude),
      name: shop.name,
      address: shop.address,
      scale: 16
    });
  },

  // ---------------- 选规格与直接购买 ----------------
  openSpecModal(e) {
    const product = e.currentTarget.dataset.item;
    const specsList = normalizeSpecsList(product.specsJson);

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

    const selectedSpecText = selectedMethod && selectedOption
      ? (selectedMethod === selectedOption ? selectedMethod : `${selectedMethod} · ${selectedOption}`)
      : (product.unit || '标准定制');

    const buyQuantity = 1;
    const totalPrice = (Number(product.price) * buyQuantity).toFixed(2);

    this.setData({
      currentProduct: product,
      specsList,
      selectedMethodIndex,
      selectedMethod,
      currentOptions,
      selectedOption,
      selectedSpecText,
      buyQuantity,
      totalPrice,
      showSpecModal: true,
      pageStyle: 'overflow: hidden;'
    });

    // 规格弹层打开时隐藏底部自定义 tabBar，避免遮挡「加入购物车」按钮
    this.setTabBarHidden(true);
  },

  closeSpecModal() {
    this.setData({ showSpecModal: false, pageStyle: '' });
    // 若切换门店弹窗仍开着，则继续保持 tabBar 隐藏
    this.setTabBarHidden(!!this.data.showShopModal);
  },

  preventBubble() {},

  // 选择加工方式
  onSelectMethod(e) {
    const index = Number(e.currentTarget.dataset.index);
    const methodItem = this.data.specsList[index];
    if (!methodItem) return;

    const options = methodItem.options || [];
    const selectedOption = options.length > 0 ? options[0] : '';
    const selectedSpecText = methodItem.name && selectedOption
      ? (methodItem.name === selectedOption ? methodItem.name : `${methodItem.name} · ${selectedOption}`)
      : (this.data.currentProduct.unit || '标准定制');

    this.setData({
      selectedMethodIndex: index,
      selectedMethod: methodItem.name,
      currentOptions: options,
      selectedOption,
      selectedSpecText
    });
  },

  // 选择加工方式下的具体规格
  onSelectOption(e) {
    if (this.data.selectedMethodIndex < 0 || !this.data.selectedMethod) {
      wx.showToast({ title: '请先选择加工方式', icon: 'none' });
      return;
    }
    const { opt } = e.currentTarget.dataset;
    const selectedMethod = this.data.selectedMethod;
    const selectedSpecText = selectedMethod && opt
      ? (selectedMethod === opt ? selectedMethod : `${selectedMethod} · ${opt}`)
      : (this.data.currentProduct.unit || '标准定制');

    this.setData({
      selectedOption: opt,
      selectedSpecText
    });
  },

  onDecQuantity() {
    if (this.data.buyQuantity <= 1) return;
    const buyQuantity = this.data.buyQuantity - 1;
    const totalPrice = (Number(this.data.currentProduct.price) * buyQuantity).toFixed(2);
    this.setData({ buyQuantity, totalPrice });
  },

  onIncQuantity() {
    if (this.data.buyQuantity >= this.data.currentProduct.stock) {
      wx.showToast({ title: '已达最大库存', icon: 'none' });
      return;
    }
    const buyQuantity = this.data.buyQuantity + 1;
    const totalPrice = (Number(this.data.currentProduct.price) * buyQuantity).toFixed(2);
    this.setData({ buyQuantity, totalPrice });
  },

  // 刷新当前门店购物车
  refreshCart() {
    const shopId = this.data.currentShopId || (this.data.shopInfo && this.data.shopInfo.id) || app.globalData.currentShopId || '';
    if (!shopId) return;

    const summary = cart.getCartSummary(shopId);
    this.setData({
      cartCount: summary.totalCount,
      cartTotalAmount: summary.totalAmount,
      cartList: summary.cartList
    });
  },

  // 规格弹窗中点击「加入购物车」
  onConfirmAddToCart() {
    if (!auth.requireLogin({ content: '登录后才能将鲜肉加入购物车' })) return;

    const product = this.data.currentProduct;
    const quantity = this.data.buyQuantity;
    const { specsList, selectedMethod, selectedOption } = this.data;

    if (specsList && specsList.length > 0) {
      if (!selectedMethod) {
        wx.showToast({ title: '请选择加工方式', icon: 'none' });
        return;
      }
      if (!selectedOption) {
        wx.showToast({ title: '请选择具体加工规格', icon: 'none' });
        return;
      }
    }

    const specInfo = this.data.selectedSpecText || product.unit;
    const shopId = product.shopId || this.data.currentShopId || '';

    const res = cart.addToCart(shopId, product, specInfo, quantity);
    this.closeSpecModal();

    if (res.success) {
      this.refreshCart();
      wx.showToast({
        title: '已入购物车🛒',
        icon: 'success'
      });
    } else {
      wx.showToast({
        title: res.message || '加购失败',
        icon: 'none'
      });
    }
  },

  // 兼容老调用
  goToCheckout() {
    this.onConfirmAddToCart();
  },

  // 打开购物车展开抽屉
  openCartDrawer() {
    this.refreshCart();
    this.setData({ showCartDrawer: true, pageStyle: 'overflow: hidden;' });
    // 抽屉打开时隐藏底部自定义 tabBar，避免遮挡「立即下单」结算栏
    this.setTabBarHidden(true);
  },

  // 关闭购物车展开抽屉
  closeCartDrawer() {
    this.setData({ showCartDrawer: false, pageStyle: '' });
    this.setTabBarHidden(!!(this.data.showShopModal || this.data.showSpecModal));
  },

  // 购物车内商品增加数量
  onCartInc(e) {
    const id = e.currentTarget.dataset.id;
    const shopId = this.data.currentShopId || (this.data.shopInfo && this.data.shopInfo.id) || app.globalData.currentShopId || '';
    cart.changeQuantity(shopId, id, 1);
    this.refreshCart();
  },

  // 购物车内商品减少数量
  onCartDec(e) {
    const id = e.currentTarget.dataset.id;
    const shopId = this.data.currentShopId || (this.data.shopInfo && this.data.shopInfo.id) || app.globalData.currentShopId || '';
    cart.changeQuantity(shopId, id, -1);
    this.refreshCart();
  },

  // 清空当前门店购物车
  onClearCart() {
    const shopId = this.data.currentShopId || (this.data.shopInfo && this.data.shopInfo.id) || app.globalData.currentShopId || '';
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

  // 悬浮球抽屉中去结算
  goToCartCheckout() {
    if (this.data.cartCount <= 0) {
      wx.showToast({ title: '购物车还是空的哦～', icon: 'none' });
      return;
    }

    const shopId = this.data.currentShopId || (this.data.shopInfo && this.data.shopInfo.id) || app.globalData.currentShopId || '';
    this.closeCartDrawer();
    wx.navigateTo({
      url: `/pages/checkout/checkout?from=cart&shopId=${shopId}`
    });
  }
});
