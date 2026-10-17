// pages/index/index.js
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const { formatImageUrl } = require('../../utils/util');
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
    totalPrice: '0.00'
  },

  /**
   * 页面数据统一在 onShow 中刷新：
   * 首次进入、以及每次从其他页签切回「鲜肉选购」时，都会重新请求本页涉及的全部接口
   * （onLoad 不再重复请求，避免首屏两次调用）
   */
  onShow() {
    this.refreshPageData();

    // 同步自定义 tabBar 选中态（弹窗打开时保持隐藏）
    const tabBar = this.getTabBar ? this.getTabBar() : null;
    if (tabBar) {
      tabBar.setData({ selected: 0, hidden: !!this.data.showShopModal });
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

  /** 写入当前生效门店（页面、globalData、缓存三处同步） */
  applyCurrentShop(shop) {
    if (!shop) return null;
    this.setData({ currentShopId: shop.id, shopInfo: shop });
    app.globalData.shopInfo = shop;
    app.globalData.currentShopId = shop.id;
    wx.setStorageSync('currentShopId', shop.id);
    return shop;
  },

  /** 从列表里还原上次选中的门店；不在当前页时按 shopId 单独查详情 */
  resolveCurrentShop(list) {
    const shops = Array.isArray(list) ? list : [];
    // 缓存优先级最高：掌柜后台「返回顾客选肉前台」会直接写入缓存指定门店
    const storedShopId = wx.getStorageSync('currentShopId') || app.globalData.currentShopId || this.data.currentShopId;
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
          return this.pickFirstShop(shops);
        })
        .catch(() => this.pickFirstShop(shops));
    }

    return Promise.resolve(this.pickFirstShop(shops));
  },

  pickFirstShop(shops) {
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
      const total = res && res.total !== undefined ? Number(res.total) : rawList.length;
      const list = reset ? rawList : this.data.shopList.concat(rawList);

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
    let specGroups = [];
    try {
      if (product.specsJson) {
        specGroups = typeof product.specsJson === 'string' ? JSON.parse(product.specsJson) : product.specsJson;
      }
    } catch (err) {
      specGroups = [];
    }

    const selectedSpecs = specGroups.map(group => group.options && group.options.length ? group.options[0] : '');
    const selectedSpecText = selectedSpecs.filter(Boolean).join(' / ');
    const buyQuantity = 1;
    const totalPrice = (Number(product.price) * buyQuantity).toFixed(2);

    this.setData({
      currentProduct: product,
      specGroups,
      selectedSpecs,
      selectedSpecText,
      buyQuantity,
      totalPrice,
      showSpecModal: true
    });
  },

  closeSpecModal() {
    this.setData({ showSpecModal: false });
  },

  preventBubble() {},

  onSelectOption(e) {
    const { groupIndex, option } = e.currentTarget.dataset;
    const selectedSpecs = [...this.data.selectedSpecs];
    selectedSpecs[groupIndex] = option;
    const selectedSpecText = selectedSpecs.filter(Boolean).join(' / ');
    this.setData({
      selectedSpecs,
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

  goToCheckout() {
    const product = this.data.currentProduct;
    const quantity = this.data.buyQuantity;
    const specInfo = this.data.selectedSpecText || product.unit;
    const shopId = product.shopId || this.data.currentShopId || '';

    this.closeSpecModal();

    // 直接带参跳转至下单结算页 (不做购物车，选完直接下单)
    wx.navigateTo({
      url: `/pages/checkout/checkout?productId=${product.id}&quantity=${quantity}&specInfo=${encodeURIComponent(specInfo)}&shopId=${shopId}`
    });
  }
});
