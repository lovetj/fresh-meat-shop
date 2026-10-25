// pages/index/index.js
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const cart = require('../../utils/cart');
const { normalizeShopStatus, isOrderBlocked } = require('../../utils/shop-status');
const { formatImageUrl, normalizeSpecsList } = require('../../utils/util');
const app = getApp();

// 「全部」的虚拟分类ID：后端分类ID为雪花字符串，0 仅用于前端占位，不会下发给接口
const ALL_CATEGORY_ID = '0';

/**
 * 商品排序方式（单选且可取消，价格在前、销量在后）
 * - sortKey 为空：未选择排序，按商品自身的排序字段（sortOrder）展示，即后端默认排序
 * - price：低→高(asc) → 高→低(desc) → 取消，循环切换
 * - sales：选中后固定从高到低，再次点击取消
 * 排序动作交给后端分页 SQL，避免只对已加载页排序导致翻页顺序错乱
 */
const DEFAULT_SORT_KEY = '';
const DEFAULT_PRICE_ORDER = 'asc';

// 定位只用于「就近排序」，不能阻塞首屏出数据：最多等待 1.2s，超时按无坐标加载
const LOCATION_TIMEOUT = 1200;
// 定位结果缓存有效期（10 分钟），避免每次切回页签都重新定位
const LOCATION_CACHE_TTL = 10 * 60 * 1000;

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

    // 排序（单选可取消）：sortKey 为空表示未选择，按商品排序字段默认排序
    sortKey: DEFAULT_SORT_KEY,
    priceOrder: DEFAULT_PRICE_ORDER,

    goodsList: [],
    pageNum: 1,
    pageSize: 4,
    hasMore: true,
    // 首屏默认进入骨架屏，避免请求发出前先闪一下空态
    loading: true,
    loadingMore: false,
    // 商品首屏加载失败标记（空态展示「重新加载」）
    loadError: false,
    // 商品列表独立滚动：滚动位置控制（切换分类/排序后回到顶部）
    goodsScrollTop: 0,
    // 商品列表下拉刷新状态
    refreshing: false,

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
   * 首次进入、以及每次从其他页签切回「商品选购」时，都会重新请求本页涉及的全部接口
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
   * 并发去重：onShow / 下拉刷新短时间重复触发时复用同一次刷新
   * @returns {Promise} 全部请求结束（单个失败不阻断其它接口）
   */
  refreshPageData() {
    if (this._pageRefreshPromise) return this._pageRefreshPromise;

    this._pageRefreshPromise = this.doRefreshPageData()
      .catch((err) => {
        console.error('[商品选购] 首屏刷新异常:', err);
      })
      .finally(() => {
        this._pageRefreshPromise = null;
        // 兜底复位，避免任一环节异常导致骨架屏常驻
        if (this.data.loading || this.data.loadingMore) {
          this.setData({ loading: false, loadingMore: false });
        }
      });

    return this._pageRefreshPromise;
  },

  /**
   * 首屏加载：门店（含定位）与分类/商品并行，不再串行等待定位授权
   * 门店确定后若与首屏使用的门店不一致，再静默按新门店刷新一次
   */
  async doRefreshPageData() {
    // 优先使用已生效门店：页面选中 > 本地缓存 > 全局
    const cachedShopId = this.data.currentShopId
      || wx.getStorageSync('currentShopId')
      || app.globalData.currentShopId
      || '';

    // 门店列表与定位在后台加载，不阻塞商品首屏
    const shopTask = this.loadShopInfo();

    if (!cachedShopId) {
      // 冷启动且没有历史门店：必须先确定门店，否则可能拉到其它门店的商品
      await shopTask;
      await Promise.all([this.loadCategories(), this.loadGoods(true)]);
      return;
    }

    // 有历史门店：立即按缓存门店渲染首屏
    if (!this.data.currentShopId) {
      this.setData({ currentShopId: cachedShopId });
    }
    await Promise.all([shopTask, this.loadCategories(), this.loadGoods(true)]);

    // 历史门店已失效或被切换：按最终门店再静默刷新一次
    const resolvedShopId = this.data.currentShopId;
    if (resolvedShopId && resolvedShopId !== cachedShopId) {
      console.log('[商品选购] 生效门店变更，按新门店重新加载:', cachedShopId, '->', resolvedShopId);
      this.setData({ currentCategoryId: ALL_CATEGORY_ID, pageNum: 1, hasMore: true });
      await Promise.all([this.loadCategories(), this.loadGoods(true)]);
    }
  },

  // 商品列表滚动到底部（触底追加分页）
  onGoodsScrollLower() {
    console.log('[商品选购] 列表触底，触发追加分页: hasMore =', this.data.hasMore, 'loading =', this.data.loading);
    this.onLoadMore();
  },

  // 商品列表下拉刷新（页面固定高度不可滚动，刷新由列表自身的 refresher 承担）
  onGoodsRefresh() {
    this.setData({ refreshing: true });
    this.refreshPageData().finally(() => {
      this.setData({ refreshing: false });
    });
  },

  /**
   * 加载门店首屏并解析当前生效商铺
   * 定位只用于就近排序：带超时 + 会话内缓存，绝不阻塞首屏出数据
   */
  loadShopInfo() {
    return new Promise((resolve) => {
      let settled = false;
      const finish = (value) => {
        if (settled) return;
        settled = true;
        resolve(value);
      };

      const load = (coords) => {
        this._coords = coords || null;
        this.loadShopPage(true)
          .then((page) => this.resolveCurrentShop(page.list))
          .then(finish)
          .catch((err) => {
            console.error('[商品选购] 加载门店列表失败，回退单店:', err);
            request.get('/shop/info', {}, { silent: true }).then((shop) => {
              if (shop) {
                this.applyCurrentShop(shop);
              }
              finish(shop || null);
            }).catch(() => finish(null));
          });
      };

      // 命中未过期的定位缓存（含「上次定位失败」的结果），直接使用
      const cachedCoords = this.getCachedCoords();
      if (cachedCoords !== undefined) {
        load(cachedCoords);
        return;
      }

      if (!wx.getLocation) {
        this.saveCachedCoords(null);
        load(null);
        return;
      }

      let located = false;
      const proceed = (coords, cacheIt) => {
        if (located) return;
        located = true;
        // 超时兜底不写缓存，下次进入可重新尝试定位
        if (cacheIt) {
          this.saveCachedCoords(coords);
        }
        load(coords);
      };

      // 首次进入会弹定位授权框，用户不响应时回调不会触发，
      // 因此最多等 1.2s，超时先按无坐标加载
      const timer = setTimeout(() => proceed(null, false), LOCATION_TIMEOUT);
      wx.getLocation({
        type: 'gcj02',
        success: (res) => {
          clearTimeout(timer);
          const coords = { latitude: res.latitude, longitude: res.longitude };
          if (located) {
            // 超时之后才拿到坐标：仅写入缓存，供下次刷新直接复用
            this.saveCachedCoords(coords);
            return;
          }
          proceed(coords, true);
        },
        fail: () => {
          clearTimeout(timer);
          proceed(null, true);
        }
      });
    });
  },

  /** 读取未过期的定位缓存；命中失败结果时返回 null，未命中返回 undefined */
  getCachedCoords() {
    const cache = this._locationCache;
    if (!cache || Date.now() - cache.time > LOCATION_CACHE_TTL) {
      return undefined;
    }
    return cache.coords || null;
  },

  /** 缓存本次定位结果，减少反复定位与授权等待 */
  saveCachedCoords(coords) {
    this._locationCache = { time: Date.now(), coords: coords || null };
  },

  /**
   * 将阶梯配送规则解析为便于展示的字段
   */
  formatShopDeliveryInfo(rawShop) {
    if (!rawShop) return null;
    // 归一化营业状态：displayStatus 1-营业中 / 2-休息中 / 3-歇业中
    const shop = normalizeShopStatus(rawShop);
    const rules = Array.isArray(shop.deliveryRules) ? shop.deliveryRules : [];
    // 仅统计有截止距离的有效阶梯（历史遗留的无截止距离规则不参与计算）
    const validRules = rules.filter(r => r.maxDistance !== null && r.maxDistance !== undefined && Number(r.maxDistance) > 0);
    let minOrder = 0;
    let minFee = 0;
    let maxDistance = 0;
    if (validRules.length > 0) {
      minOrder = validRules[0].minOrderAmount !== undefined ? validRules[0].minOrderAmount : 0;
      minFee = validRules[0].deliveryFee !== undefined ? validRules[0].deliveryFee : 0;
      validRules.forEach(r => {
        const max = Number(r.maxDistance) || 0;
        if (max > maxDistance) {
          maxDistance = max;
        }
      });
    }
    // 未开通同城配送时，只展示快递/自提能力
    const merchantEnabled = shop.merchantDeliveryEnabled !== undefined && shop.merchantDeliveryEnabled !== null
      ? Boolean(shop.merchantDeliveryEnabled)
      : true;
    const deliveryRangeText = !merchantEnabled
      ? '快递配送'
      : (validRules.length === 0 ? '同城配送' : `${maxDistance || 5}km 内`);
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
      console.error('[商品选购] 加载门店分页失败:', err);
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
      if (selectedShop.displayStatus === 3) {
        wx.showToast({ title: '该门店歇业中，暂不接单', icon: 'none' });
      } else {
        wx.showToast({ title: `已切换至: ${selectedShop.name}`, icon: 'none' });
      }
      // 切换门店后重置选中分类，并重新刷新该门店专属的分类和商品
      this.setData({ currentCategoryId: ALL_CATEGORY_ID });
      this.resetGoodsScroll();
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
    console.log('[商品选购] 开始请求门店分类列表: /category/list', params);
    return request.get('/category/list', params, { silent: true }).then((res) => {
      console.log('[商品选购] 分类列表返回结果:', res);
      const rawList = Array.isArray(res) ? res : (res && Array.isArray(res.data) ? res.data : []);
      // 分类ID统一为字符串，避免与 currentCategoryId 做 === 比较时类型不一致
      const categories = rawList.map((item) => ({ ...item, id: String(item.id) }));
      this.setData({ categories });
    }).catch((err) => {
      console.error('[商品选购] 加载分类列表异常:', err);
    });
  },

  loadGoods(reset = false, attempt = 0) {
    // 追加分页时避免重复请求；重置请求（页签切回/分类切换）始终发起
    if (!reset && this.data.loading) return Promise.resolve();

    // 请求序号：快速切换页签/分类时，丢弃已过期的响应
    const seq = (this._goodsReqSeq = (this._goodsReqSeq || 0) + 1);
    const pageNum = reset ? 1 : this.data.pageNum;
    // 仅追加分页展示底部加载动画，重置首屏不展示（首屏用骨架屏）
    const loadingPatch = { loading: true, loadingMore: !reset };
    if (reset) {
      loadingPatch.loadError = false;
    }
    this.setData(loadingPatch);

    const params = {
      pageNum,
      pageSize: this.data.pageSize
    };
    // 未选择排序时不传排序参数，由后端按商品排序字段（sortOrder）默认排序
    if (this.data.sortKey === 'price') {
      params.sortBy = 'price';
      params.sortOrder = this.data.priceOrder;
    } else if (this.data.sortKey === 'sales') {
      params.sortBy = 'sales';
      params.sortOrder = 'desc';
    }
    if (this.data.currentShopId) {
      params.shopId = this.data.currentShopId;
    }
    if (this.data.currentCategoryId && this.data.currentCategoryId !== ALL_CATEGORY_ID) {
      params.categoryId = this.data.currentCategoryId;
    }
    if (this.data.keyword && this.data.keyword.trim()) {
      params.keyword = this.data.keyword.trim();
    }

    console.log('[商品选购] 发起商品请求:', params, reset ? '(重置首屏)' : '(追加分页)');

    return request.get('/product/list', params).then((res) => {
      // 已有更新的请求在途，忽略本次过期数据
      if (seq !== this._goodsReqSeq) return;
      console.log('[商品选购] 商品列表返回结果:', res);
      const rawList = Array.isArray(res) ? res : ((res && (res.list || res.records)) || []);
      const list = rawList.map((item) => ({
        ...item,
        coverImage: formatImageUrl(item.coverImage)
      }));
      const total = res && res.total !== undefined ? res.total : rawList.length;
      const currentList = reset ? list : this.data.goodsList.concat(list);
      const hasMore = currentList.length < total;

      this.setData({
        goodsList: currentList,
        pageNum: pageNum + 1,
        hasMore,
        loading: false,
        loadingMore: false,
        loadError: false
      });
    }).catch((err) => {
      if (seq !== this._goodsReqSeq) return;
      console.error('[商品选购] 加载商品列表异常:', err);
      // 首屏首次失败时静默重试一次，规避冷启动首帧请求失败导致的空白
      if (reset && attempt < 1 && this.data.goodsList.length === 0) {
        console.warn('[商品选购] 商品首屏加载失败，300ms 后自动重试一次');
        return new Promise((r) => setTimeout(r, 300)).then(() => this.loadGoods(true, attempt + 1));
      }
      this.setData({
        loading: false,
        loadingMore: false,
        loadError: reset ? true : this.data.loadError
      });
    });
  },

  /** 空态点击「重新加载」 */
  onRetryLoadGoods() {
    this.setData({ loadError: false, pageNum: 1, hasMore: true });
    this.loadGoods(true);
  },

  /**
   * 应用排序（单选）：切换后回到第 1 页并展示骨架屏，
   * 由后端按新排序重新分页返回，保证翻页顺序正确
   */
  applySort(sortKey, priceOrder) {
    const nextPriceOrder = priceOrder || this.data.priceOrder;
    if (sortKey === this.data.sortKey && nextPriceOrder === this.data.priceOrder) return;

    this.setData({
      sortKey,
      priceOrder: nextPriceOrder,
      pageNum: 1,
      hasMore: true,
      goodsList: [],
      loading: true
    }, () => {
      this.resetGoodsScroll();
      this.loadGoods(true);
    });
  },

  /**
   * 价格排序：低→高 → 高→低 → 取消（还原默认排序），循环切换
   */
  onSortPrice() {
    if (this.data.sortKey !== 'price') {
      this.applySort('price', DEFAULT_PRICE_ORDER);
      return;
    }
    if (this.data.priceOrder === 'asc') {
      this.applySort('price', 'desc');
      return;
    }
    this.applySort(DEFAULT_SORT_KEY);
  },

  /** 销量排序：选中后从高到低，再次点击取消（还原默认排序） */
  onSortSales() {
    this.applySort(this.data.sortKey === 'sales' ? DEFAULT_SORT_KEY : 'sales');
  },

  onSelectCategory(e) {
    const rawId = e.currentTarget.dataset.id;
    // dataset 取值可能是字符串或数字，统一规范为字符串（「全部」为 '0'）
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
      // 切换分类后商品列表回到顶部，避免停留在上一分类的滚动位置
      this.resetGoodsScroll();
      this.loadGoods(true);
    });
  },

  /**
   * 商品列表回到顶部：
   * scroll-view 的 scroll-top 值未变化时不会触发滚动，这里交替一个极小值强制生效
   */
  resetGoodsScroll() {
    this.setData({ goodsScrollTop: this.data.goodsScrollTop === 0 ? 1 : 0 });
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
      wx.showToast({ title: '未配置坐标', icon: 'none' });
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

  /** 歇业提示 */
  tipShopClosed() {
    wx.showModal({
      title: '门店已歇业',
      content: (this.data.shopInfo && this.data.shopInfo.statusTip) || '该门店当前处于歇业状态，暂不接单。可切换其他门店或稍后再来～',
      showCancel: false,
      confirmText: '我知道了'
    });
  },

  /**
   * 点击右上角营业状态标签：展示该门店的营业时间
   * （营业时间不再常驻展示在店铺横幅上，避免信息堆叠）
   */
  onTapShopStatus() {
    const shop = this.data.shopInfo || {};
    const statusText = shop.statusText || '营业中';
    const hours = (shop.businessHours || '').trim() || '06:30 - 20:00';

    wx.showModal({
      title: statusText,
      content: `营业时间：${hours}`,
      showCancel: false,
      confirmText: '我知道了',
      confirmColor: '#FF6B6B'
    });
  },

  // ---------------- 选规格与直接购买 ----------------
  openSpecModal(e) {
    // 歇业中相当于关门：不允许加购
    if (isOrderBlocked(this.data.shopInfo)) {
      this.tipShopClosed();
      return;
    }
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

  // 规格弹窗中点击「加入购物车」
  onConfirmAddToCart() {
    if (!auth.requireLogin({ content: '登录后才能将商品加入购物车' })) return;

    // 歇业中相当于关门：不允许加购
    if (isOrderBlocked(this.data.shopInfo)) {
      this.tipShopClosed();
      return;
    }

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

    // 歇业中相当于关门，直接拦截下单入口
    if (isOrderBlocked(this.data.shopInfo)) {
      this.tipShopClosed();
      return;
    }

    const shopId = this.data.currentShopId || (this.data.shopInfo && this.data.shopInfo.id) || app.globalData.currentShopId || '';
    this.closeCartDrawer();
    wx.navigateTo({
      url: `/pages/checkout/checkout?from=cart&shopId=${shopId}`
    });
  }
});
