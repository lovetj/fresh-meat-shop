// pages/orders/orders.js
const app = getApp();
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const { formatImageUrl } = require('../../utils/util');

Page({
  data: {
    // 状态页签与「掌柜订单处理」保持一致
    tabs: [
      { label: '全部', status: '', countKey: 'allCount' },
      { label: '待接单', status: 1, countKey: 'pendingAcceptCount' },
      { label: '待配货/自提', status: 2, countKey: 'acceptedCount' },
      { label: '配送中', status: 3, countKey: 'deliveringCount' },
      { label: '待付款', status: 0, countKey: 'pendingPayCount' },
      { label: '已完成', status: 4, countKey: 'completedCount' },
      // 取消/拒单：同时查询 5(已取消) 与 6(已拒单)
      { label: '取消/拒单', status: 5, queryStatus: '5,6', countKey: 'closedCount' }
    ],
    statusCounts: {},
    currentStatus: '',
    // 配送方式筛选: '' 全部, 1 到店自取, 2 商家配送, 4 快递配送（与掌柜订单处理保持一致）
    deliveryType: '',
    orderList: [],
    pageNum: 1,
    pageSize: 10,
    hasMore: true,
    loading: false,
    loadingMore: false
  },

  onLoad(options) {
    if (options.status !== undefined && options.status !== '') {
      this.setData({ currentStatus: Number(options.status) });
    }
  },

  onShow() {
    // 从「我的」页进入时携带的状态筛选（tabBar 页无法带参，用 globalData 传递）
    const pendingStatus = app.globalData.orderStatusFilter;
    if (pendingStatus !== undefined && pendingStatus !== null) {
      app.globalData.orderStatusFilter = null;
      this.setData({ currentStatus: pendingStatus === '' ? '' : Number(pendingStatus) });
    }
    // 同步自定义 tabBar 选中态
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 1 });
    }

    // 账号/身份切换后清空上一个用户的订单数据，避免串号展示
    const identityKey = this.getIdentityKey();
    if (this._identityKey !== undefined && this._identityKey !== identityKey) {
      this.setData({ orderList: [], statusCounts: {}, pageNum: 1, hasMore: true });
    }
    this._identityKey = identityKey;

    // 未登录（无生效身份或无对应 Token）不允许查看订单，弹窗引导登录
    if (!auth.isLoggedIn() || !auth.getActiveToken()) {
      this.setData({ orderList: [], statusCounts: {} });
      auth.requireLogin({
        title: '请先登录',
        content: '登录后才能查看我的订单'
      });
      return;
    }

    this.loadOrders(true);
  },

  /**
   * 当前页签实际提交的状态查询值
   * 「取消/拒单」需同时命中已取消(5)与已拒单(6)
   */
  getQueryStatus() {
    if (this.data.currentStatus === '') return '';
    const tab = (this.data.tabs || []).find((t) => t.status === this.data.currentStatus);
    return (tab && tab.queryStatus) || this.data.currentStatus;
  },

  /**
   * 当前生效身份标识（角色 + 用户ID）
   * 用于识别账号切换，确保订单查询始终落在当前登录用户身上
   */
  getIdentityKey() {
    const role = auth.getActiveRole();
    if (!role) return '';
    const user = auth.getUserInfo() || {};
    return `${role}:${user.userId || user.id || ''}`;
  },

  onPullDownRefresh() {
    this.loadOrders(true).then(() => {
      wx.stopPullDownRefresh();
    });
  },

  onSwitchTab(e) {
    const status = e.currentTarget.dataset.status;
    this.setData({ currentStatus: status }, () => {
      this.loadOrders(true);
    });
  },

  /**
   * 配送方式筛选（全部/到店自取/同城配送/快递配送）
   * 再次点击当前项可取消，恢复「全部配送」
   */
  onDeliveryTypeChange(e) {
    const type = e.currentTarget.dataset.type;
    const nextType = this.data.deliveryType === type ? '' : type;
    this.setData({
      deliveryType: nextType,
      orderList: [],
      pageNum: 1,
      hasMore: true
    });
    this.loadOrders(true);
  },

  loadOrders(reset = false) {
    // 严格使用「当前生效身份」的 Token，保证只查询当前登录用户的订单
    const token = auth.getActiveToken();
    if (!auth.isLoggedIn() || !token) {
      this.setData({ orderList: [], loading: false, loadingMore: false, statusCounts: {} });
      return Promise.resolve();
    }

    // 请求序号：快速切换页签/筛选时，丢弃已过期的响应
    const seq = (this._orderReqSeq = (this._orderReqSeq || 0) + 1);
    const identityKey = this.getIdentityKey();
    const pageNum = reset ? 1 : this.data.pageNum;
    // 仅追加分页展示底部加载动画
    this.setData({ loading: true, loadingMore: !reset });

    const params = {
      pageNum,
      pageSize: this.data.pageSize
    };
    const queryStatus = this.getQueryStatus();
    if (queryStatus !== '') {
      params.status = queryStatus;
    }
    if (this.data.deliveryType !== '' && this.data.deliveryType !== undefined && this.data.deliveryType !== null) {
      params.deliveryType = this.data.deliveryType;
    }

    return request.get('/order/list', params, {
      // 显式带上当前用户身份 Token，避免请求层回退到其他账号的登录态
      header: { Authorization: `Bearer ${token}` }
    }).then((res) => {
      // 已有更新的请求在途或账号已切换，忽略本次过期数据
      if (seq !== this._orderReqSeq || identityKey !== this.getIdentityKey()) return;
      // 兜底校验：只展示归属于当前登录用户的订单，防止异常数据串号
      const currentUser = auth.getUserInfo() || {};
      const currentUserId = String(currentUser.userId || currentUser.id || '');
      const rawList = res.list || [];
      const list = rawList
        .filter((o) => !currentUserId || !o.userId || String(o.userId) === currentUserId)
        .map((o) => ({
          ...o,
          items: (o.items || []).map((item) => ({
            ...item,
            productImage: formatImageUrl(item.productImage)
          }))
        }));
      const total = res.total || 0;
      const currentList = reset ? list : this.data.orderList.concat(list);
      const hasMore = rawList.length > 0 && currentList.length < total;

      this.setData({
        orderList: currentList,
        pageNum: pageNum + 1,
        hasMore,
        loading: false,
        loadingMore: false
      });
      // 重置列表时同步刷新状态角标数量
      if (reset) this.loadStatusCounts();
    }).catch(() => {
      if (seq !== this._orderReqSeq) return;
      this.setData({ loading: false, loadingMore: false });
    });
  },

  /**
   * 加载各状态订单数量，用于顶部标签角标
   */
  loadStatusCounts() {
    const token = auth.getActiveToken();
    if (!auth.isLoggedIn() || !token) {
      this.setData({ statusCounts: {} });
      return Promise.resolve();
    }
    const identityKey = this.getIdentityKey();
    return request.get('/order/status-counts', {}, {
      silent: true,
      // 角标数量同样只统计当前登录用户
      header: { Authorization: `Bearer ${token}` }
    })
      .then((res) => {
        if (identityKey !== this.getIdentityKey()) return;
        const counts = res || {};
        this.setData({
          statusCounts: {
            allCount: counts.allCount || 0,
            pendingPayCount: counts.pendingPayCount || 0,
            pendingAcceptCount: counts.pendingAcceptCount || 0,
            acceptedCount: counts.acceptedCount || 0,
            deliveringCount: counts.deliveringCount || 0,
            completedCount: counts.completedCount || 0,
            closedCount: counts.closedCount || 0
          }
        });
      })
      .catch(() => {});
  },

  onLoadMore() {
    if (this.data.hasMore && !this.data.loading) {
      this.loadOrders(false);
    }
  },

  goToDetail(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: `/pages/order-detail/order-detail?id=${id}`
    });
  },

  goToHome() {
    wx.switchTab({
      url: '/pages/index/index'
    });
  },

  preventBubble() {},

  /** 订单不可取消时（商家已接单/配送中）明确提示并刷新列表状态 */
  showCancelNotAllowed(message) {
    wx.showModal({
      title: '无法取消订单',
      content: message || '当前订单状态不可取消',
      showCancel: false,
      confirmText: '我知道了',
      confirmColor: '#FF6B6B',
      success: () => {
        this.loadOrders(true);
      }
    });
  },

  onCancelOrder(e) {
    const id = e.currentTarget.dataset.id;
    const order = (this.data.orderList || []).find((item) => item.id === id) || {};
    // 与后端校验保持一致：仅待支付(0)/待接单(1)可取消
    if (order.canCancel !== true) {
      this.showCancelNotAllowed('商家已接单或订单状态已变更，当前订单不可取消');
      return;
    }

    wx.showModal({
      title: '取消确认',
      content: '确定要取消该鲜肉订单吗？',
      success: (res) => {
        if (!res.confirm) return;
        wx.showLoading({ title: '取消中...' });
        // silent: true 由本页统一处理失败提示，避免被 hideLoading 吞掉
        request.post(`/order/cancel/${id}`, { reason: '顾客主动取消' }, { silent: true }).then(() => {
          wx.hideLoading();
          wx.showToast({ title: '订单已取消', icon: 'success' });
          this.loadOrders(true);
        }).catch((err) => {
          wx.hideLoading();
          this.showCancelNotAllowed((err && err.message) || '当前订单状态不可取消');
        });
      }
    });
  },

  onPayOrder(e) {
    const id = e.currentTarget.dataset.id;
    wx.showLoading({ title: '拉取支付...' });
    request.post(`/payment/wx/prepay/${id}`).then((payParams) => {
      wx.hideLoading();
      if (payParams.isMock) {
        wx.showModal({
          title: '微信支付 (测试模拟)',
          content: `应付金额：¥${payParams.payAmount}\n确认完成模拟付款？`,
          confirmText: '确认付款',
          success: (mRes) => {
            if (mRes.confirm) {
              request.post(`/payment/mock/success/${id}`).then(() => {
                wx.showToast({ title: '支付成功！', icon: 'success' });
                this.loadOrders(true);
              });
            }
          }
        });
      } else {
        wx.requestPayment({
          timeStamp: payParams.timeStamp,
          nonceStr: payParams.nonceStr,
          package: payParams.packageVal,
          signType: payParams.signType || 'RSA',
          paySign: payParams.paySign,
          success: () => {
            wx.showToast({ title: '支付成功', icon: 'success' });
            this.loadOrders(true);
          }
        });
      }
    }).catch(() => {
      wx.hideLoading();
    });
  },

  onConfirmOrder(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '确认收货',
      content: '确认已收到新鲜肉品？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '确认中...' });
          request.post(`/order/confirm/${id}`).then(() => {
            wx.hideLoading();
            wx.showToast({ title: '订单已完成！', icon: 'success' });
            this.loadOrders(true);
          }).catch(() => {
            wx.hideLoading();
          });
        }
      }
    });
  }
});
