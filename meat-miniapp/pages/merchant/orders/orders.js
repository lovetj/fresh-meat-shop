// pages/merchant/orders/orders.js
const request = require('../../../utils/request');
const auth = require('../../../utils/auth');
const { formatImageUrl } = require('../../../utils/util');

Page({
  data: {
    tabs: [
      { label: '全部', status: '' },
      { label: '待接单', status: 1 },
      { label: '待配送/自取', status: 2 },
      { label: '配送中', status: 3 },
      { label: '已完成', status: 4 }
    ],
    currentStatus: '',
    shopId: '',
    orderList: [],
    page: 1,
    size: 10,
    hasMore: true,
    loading: false
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
    if (options && options.status !== undefined) {
      this.setData({
        currentStatus: options.status === '' ? '' : parseInt(options.status, 10),
        shopId
      });
    } else {
      this.setData({ shopId });
    }
    this.loadOrders(true);
  },

  onPullDownRefresh() {
    this.loadOrders(true);
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

  loadOrders(refresh = false) {
    if (this.data.loading) return;
    const page = refresh ? 1 : this.data.page;
    this.setData({ loading: true });

    const params = {
      page: page,
      size: this.data.size
    };
    if (this.data.shopId) {
      params.shopId = this.data.shopId;
    }
    if (this.data.currentStatus !== '') {
      params.status = this.data.currentStatus;
    }

    request.get('/order/admin/list', params)
      .then(res => {
        const rawList = res.records || [];
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
          hasMore: orderList.length < res.total,
          loading: false
        });
      })
      .catch(err => {
        console.error('加载掌柜订单失败', err);
        this.setData({ loading: false });
      })
      .finally(() => {
        wx.stopPullDownRefresh();
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

  preventBubble() {},

  makePhoneCall(e) {
    const phone = e.currentTarget.dataset.phone;
    if (phone) {
      wx.makePhoneCall({
        phoneNumber: phone
      });
    }
  },

  // 掌柜操作：接单
  onAcceptOrder(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '确认接单',
      content: '确认接下此肉铺订单并开始切配？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/accept/${id}`)
            .then(() => {
              wx.showToast({ title: '已成功接单', icon: 'success' });
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

  // 掌柜操作：开始配送 / 备齐可自提
  onDeliverOrder(e) {
    const id = e.currentTarget.dataset.id;
    const type = e.currentTarget.dataset.type;
    const actionText = type === 1 ? '通知备齐自提' : '开始同城配送';

    wx.showModal({
      title: '操作确认',
      content: `确认将此订单状态更新为【${actionText}】？`,
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '更新中...' });
          request.post(`/order/admin/deliver/${id}`)
            .then(() => {
              wx.showToast({ title: '已更新状态', icon: 'success' });
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
    wx.showModal({
      title: '订单完成确认',
      content: '确认该笔鲜肉订单已配送送达或顾客已提货？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/finish/${id}`)
            .then(() => {
              wx.showToast({ title: '订单已圆满完成', icon: 'success' });
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
  }
});
