// pages/orders/orders.js
const app = getApp();
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const { formatImageUrl } = require('../../utils/util');

Page({
  data: {
    tabs: [
      { label: '全部', status: '' },
      { label: '待支付', status: 0 },
      { label: '待接单', status: 1 },
      { label: '待收货/自提', status: 2 },
      { label: '已完成', status: 4 }
    ],
    currentStatus: '',
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
    this.loadOrders(true);
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

  loadOrders(reset = false) {
    if (!auth.isLoggedIn()) {
      this.setData({ orderList: [], loading: false, loadingMore: false });
      return Promise.resolve();
    }

    // 请求序号：快速切换页签/筛选时，丢弃已过期的响应
    const seq = (this._orderReqSeq = (this._orderReqSeq || 0) + 1);
    const pageNum = reset ? 1 : this.data.pageNum;
    // 仅追加分页展示底部加载动画
    this.setData({ loading: true, loadingMore: !reset });

    const params = {
      pageNum,
      pageSize: this.data.pageSize
    };
    if (this.data.currentStatus !== '') {
      params.status = this.data.currentStatus;
    }

    return request.get('/order/list', params).then((res) => {
      // 已有更新的请求在途，忽略本次过期数据
      if (seq !== this._orderReqSeq) return;
      const list = (res.list || []).map((o) => ({
        ...o,
        items: (o.items || []).map((item) => ({
          ...item,
          productImage: formatImageUrl(item.productImage)
        }))
      }));
      const total = res.total || 0;
      const currentList = reset ? list : this.data.orderList.concat(list);
      const hasMore = currentList.length < total;

      this.setData({
        orderList: currentList,
        pageNum: pageNum + 1,
        hasMore,
        loading: false,
        loadingMore: false
      });
    }).catch(() => {
      if (seq !== this._orderReqSeq) return;
      this.setData({ loading: false, loadingMore: false });
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
      url: `/pages/order-detail/order-detail?id=${id}`
    });
  },

  goToHome() {
    wx.switchTab({
      url: '/pages/index/index'
    });
  },

  preventBubble() {},

  onCancelOrder(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '取消确认',
      content: '确定要取消该鲜肉订单吗？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '取消中...' });
          request.post(`/order/cancel/${id}`, { reason: '顾客主动取消' }).then(() => {
            wx.hideLoading();
            wx.showToast({ title: '订单已取消', icon: 'success' });
            this.loadOrders(true);
          }).catch(() => {
            wx.hideLoading();
          });
        }
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
