// pages/merchant/orders/orders.js
const request = require('../../../utils/request');
const auth = require('../../../utils/auth');
const { formatImageUrl } = require('../../../utils/util');

Page({
  data: {
    tabs: [
      { label: '全部', status: '', countKey: 'allCount' },
      { label: '待接单', status: 1, countKey: 'pendingAcceptCount' },
      { label: '待配货/自提', status: 2, countKey: 'acceptedCount' },
      { label: '配送中', status: 3, countKey: 'deliveringCount' },
      { label: '待付款', status: 0, countKey: 'pendingPayCount' },
      { label: '已完成', status: 4, countKey: 'completedCount' },
      { label: '取消/拒单', status: 5, countKey: 'closedCount' }
    ],
    statusCounts: {
      allCount: 0,
      pendingAcceptCount: 0,
      acceptedCount: 0,
      deliveringCount: 0,
      pendingPayCount: 0,
      completedCount: 0,
      closedCount: 0
    },
    currentStatus: '',
    shopId: '',

    // 配送方式筛选: '' 全部, 1 自取, 2 商家配送
    deliveryType: '',
    keyword: '',

    orderList: [],
    page: 1,
    size: 10,
    hasMore: true,
    loading: false,

    // 弹窗状态
    showVerifyModal: false,
    inputPickupCode: '',

    showPriceModal: false,
    priceOrder: null,
    newPayAmount: '',
    priceReason: ''
  },

  onLoad(options) {
    if (!auth.isAdmin()) {
      wx.showToast({ title: '无掌柜权限', icon: 'none' });
      setTimeout(() => {
        wx.switchTab({ url: '/pages/mine/mine' });
      }, 1000);
      return;
    }
    const shopId = (options && options.shopId) || wx.getStorageSync('adminShopId') || '';
    const status = options && options.status !== undefined && options.status !== '' 
      ? parseInt(options.status, 10) 
      : '';

    this.setData({
      currentStatus: status,
      shopId: shopId
    });

    this.loadStatusCounts();
    this.loadOrders(true);
  },

  onShow() {
    this.loadStatusCounts();
  },

  onPullDownRefresh() {
    Promise.all([
      this.loadStatusCounts(),
      this.loadOrders(true)
    ]).finally(() => {
      wx.stopPullDownRefresh();
    });
  },

  loadStatusCounts() {
    const params = {};
    if (this.data.shopId) {
      params.shopId = this.data.shopId;
    }
    return request.get('/order/admin/status-counts', params, { silent: true })
      .then((res) => {
        if (res) {
          this.setData({
            statusCounts: {
              allCount: res.allCount || 0,
              pendingAcceptCount: res.pendingAcceptCount || 0,
              acceptedCount: res.acceptedCount || 0,
              deliveringCount: res.deliveringCount || 0,
              pendingPayCount: res.pendingPayCount || 0,
              completedCount: res.completedCount || 0,
              closedCount: res.closedCount || 0
            }
          });
        }
      }).catch(() => {});
  },

  onSwitchTab(e) {
    const status = e.currentTarget.dataset.status;
    this.setData({
      currentStatus: status,
      orderList: [],
      page: 1,
      hasMore: true
    });
    this.loadOrders(true);
  },

  onDeliveryTypeChange(e) {
    const type = e.currentTarget.dataset.type;
    this.setData({
      deliveryType: this.data.deliveryType === type ? '' : type,
      orderList: [],
      page: 1,
      hasMore: true
    });
    this.loadOrders(true);
  },

  onKeywordInput(e) {
    this.setData({
      keyword: e.detail.value
    });
  },

  onSearchConfirm() {
    this.setData({
      orderList: [],
      page: 1,
      hasMore: true
    });
    this.loadOrders(true);
  },

  onClearSearch() {
    this.setData({
      keyword: '',
      orderList: [],
      page: 1,
      hasMore: true
    });
    this.loadOrders(true);
  },

  loadOrders(refresh = false) {
    const page = refresh ? 1 : this.data.page;
    // 请求序号：搜索/切页签时丢弃已过期的响应，避免旧数据覆盖新条件的结果
    const seq = (this._reqSeq = (this._reqSeq || 0) + 1);
    this.setData({ loading: true });

    const params = {
      pageNum: page,
      pageSize: this.data.size
    };
    if (this.data.shopId) {
      params.shopId = this.data.shopId;
    }
    if (this.data.currentStatus !== '') {
      params.status = this.data.currentStatus;
    }
    if (this.data.deliveryType !== '') {
      params.deliveryType = this.data.deliveryType;
    }
    if (this.data.keyword && this.data.keyword.trim()) {
      params.keyword = this.data.keyword.trim();
    }

    return request.get('/order/admin/list', params)
      .then(res => {
        if (seq !== this._reqSeq) return;
        const rawList = res.records || res.list || [];
        const list = rawList.map(o => ({
          ...o,
          items: (o.items || []).map(item => ({
            ...item,
            productImage: formatImageUrl(item.productImage)
          }))
        }));
        const orderList = refresh ? list : this.data.orderList.concat(list);
        this.setData({
          orderList: orderList,
          page: page + 1,
          hasMore: orderList.length < (res.total || 0),
          loading: false
        });
      })
      .catch(err => {
        if (seq !== this._reqSeq) return;
        console.error('加载掌柜订单失败', err);
        this.setData({ loading: false });
      });
  },

  onLoadMore() {
    if (this.data.hasMore && !this.data.loading) {
      this.loadOrders(false);
    }
  },

  goToDetail(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: `/pages/order-detail/order-detail?id=${id}&isAdmin=1`
    });
  },

  preventBubble() {},

  copyOrderNo(e) {
    const orderNo = e.currentTarget.dataset.no;
    if (orderNo) {
      wx.setClipboardData({
        data: orderNo,
        success: () => {
          wx.showToast({ title: '已复制订单号', icon: 'none' });
        }
      });
    }
  },

  // ================= 快捷提货码核销 =================
  openVerifyModal() {
    this.setData({
      showVerifyModal: true,
      inputPickupCode: ''
    });
  },

  closeVerifyModal() {
    this.setData({
      showVerifyModal: false,
      inputPickupCode: ''
    });
  },

  onPickupCodeInput(e) {
    this.setData({
      inputPickupCode: e.detail.value.trim()
    });
  },

  confirmVerifyPickup() {
    const code = this.data.inputPickupCode;
    if (!code) {
      wx.showToast({ title: '请输入6位提货码', icon: 'none' });
      return;
    }
    wx.showLoading({ title: '核销中...' });
    request.post('/order/admin/verify-pickup', {
      pickupCode: code,
      shopId: this.data.shopId || undefined
    }).then((res) => {
      wx.hideLoading();
      this.closeVerifyModal();
      wx.showModal({
        title: '核销成功',
        content: `订单[${res.orderNo}]核销提货完成！`,
        showCancel: false,
        success: () => {
          this.loadStatusCounts();
          this.loadOrders(true);
        }
      });
    }).catch(err => {
      wx.hideLoading();
      wx.showToast({ title: err.message || '核销失败', icon: 'none' });
    });
  },

  // 掌柜操作：接单
  onAcceptOrder(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '确认接单',
      content: '确认接下此鲜肉订单并开始切配？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/accept/${id}`)
            .then(() => {
              wx.showToast({ title: '已成功接单', icon: 'success' });
              this.loadStatusCounts();
              this.loadOrders(true);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '接单失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  // 掌柜操作：拒单
  onRejectOrder(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '拒单确认',
      editable: true,
      placeholderText: '请输入拒单原因（如：当日肉品售罄）',
      success: (res) => {
        if (res.confirm) {
          const reason = res.content ? res.content.trim() : '商家暂时无法接单';
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/reject/${id}`, { reason: reason })
            .then(() => {
              wx.showToast({ title: '已拒单并原路退款', icon: 'success' });
              this.loadStatusCounts();
              this.loadOrders(true);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '拒单失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  // 掌柜操作：开始配送 / 通知备齐
  onDeliverOrder(e) {
    const id = e.currentTarget.dataset.id;
    const type = e.currentTarget.dataset.type;
    const actionText = type === 1 ? '通知备齐可自提' : '开始同城配送';

    wx.showModal({
      title: '操作确认',
      content: `确认将此订单状态更新为【${actionText}】？`,
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '更新中...' });
          request.post(`/order/admin/deliver/${id}`)
            .then(() => {
              wx.showToast({ title: '已更新状态', icon: 'success' });
              this.loadStatusCounts();
              this.loadOrders(true);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '更新失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  // 掌柜操作：核销 / 确认送达完成
  onFinishOrder(e) {
    const id = e.currentTarget.dataset.id;
    const type = e.currentTarget.dataset.type;
    const tip = type === 1 ? '确认顾客已到店提走鲜肉？' : '确认该笔鲜肉订单已配送送达？';
    wx.showModal({
      title: '订单完成确认',
      content: tip,
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/finish/${id}`)
            .then(() => {
              wx.showToast({ title: '订单已完成', icon: 'success' });
              this.loadStatusCounts();
              this.loadOrders(true);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '操作失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  // 掌柜操作：主动取消退款
  onAdminCancelOrder(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '取消并退款确认',
      editable: true,
      placeholderText: '请输入取消退款原因(如: 顾客电话联系退款)',
      success: (res) => {
        if (res.confirm) {
          const reason = res.content ? res.content.trim() : '掌柜协商取消退款';
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/cancel/${id}`, { reason: reason })
            .then(() => {
              wx.showToast({ title: '已取消退款', icon: 'success' });
              this.loadStatusCounts();
              this.loadOrders(true);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '取消失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  // 掌柜操作：改价弹窗
  openPriceModal(e) {
    const item = e.currentTarget.dataset.item;
    this.setData({
      showPriceModal: true,
      priceOrder: item,
      newPayAmount: item.payAmount,
      priceReason: ''
    });
  },

  closePriceModal() {
    this.setData({
      showPriceModal: false,
      priceOrder: null,
      newPayAmount: '',
      priceReason: ''
    });
  },

  onPriceInput(e) {
    this.setData({ newPayAmount: e.detail.value });
  },

  onPriceReasonInput(e) {
    this.setData({ priceReason: e.detail.value });
  },

  confirmChangePrice() {
    const order = this.data.priceOrder;
    const amount = parseFloat(this.data.newPayAmount);
    if (isNaN(amount) || amount <= 0) {
      wx.showToast({ title: '请输入有效金额', icon: 'none' });
      return;
    }
    wx.showLoading({ title: '提交中...' });
    request.post(`/order/admin/change-price/${order.id}`, {
      newPayAmount: amount,
      reason: this.data.priceReason.trim()
    }).then(() => {
      wx.hideLoading();
      wx.showToast({ title: '改价成功', icon: 'success' });
      this.closePriceModal();
      this.loadOrders(true);
    }).catch(err => {
      wx.hideLoading();
      wx.showToast({ title: err.message || '改价失败', icon: 'none' });
    });
  }
});
