// pages/merchant/products/list/index.js
const request = require('../../../../utils/request');
const auth = require('../../../../utils/auth');
const { formatImageUrl } = require('../../../../utils/util');

Page({
  data: {
    shopId: '',
    shopList: [],
    shopIndex: 0,
    categories: [],
    selectedCategoryId: '', // '' 表示全部分类
    keyword: '',
    statusFilter: '', // '' 全部, 1 上架, 0 下架
    productList: [],
    pageNum: 1,
    pageSize: 10,
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
    const initialShopId = (options && options.shopId) || wx.getStorageSync('adminShopId') || '';
    this.setData({ shopId: initialShopId });

    this.loadShops(initialShopId).then(() => {
      this.loadCategories(this.data.shopId);
      this.loadProducts(true);
    });
  },

  onShow() {
    // 每次从编辑/新增/分类管理页返回时刷新分类与商品
    if (auth.isAdmin() && this.data.shopId) {
      this.loadCategories(this.data.shopId);
      this.loadProducts(true);
    }
  },

  onPullDownRefresh() {
    Promise.all([
      this.loadCategories(this.data.shopId),
      this.loadProducts(true)
    ]).finally(() => {
      wx.stopPullDownRefresh();
    });
  },

  /**
   * 加载掌柜管辖的店铺列表并选中当前店铺
   */
  loadShops(preferShopId) {
    return request.get('/shop/admin/my-shops', {}, { silent: true })
      .then(res => {
        const list = Array.isArray(res) ? res : [];
        let shopIdx = -1;
        const targetId = preferShopId || this.data.shopId || wx.getStorageSync('adminShopId');
        if (targetId) {
          shopIdx = list.findIndex(s => s.id === targetId);
        }
        if (shopIdx < 0 && list.length > 0) {
          shopIdx = 0;
        }

        const selectedShopId = shopIdx >= 0 ? list[shopIdx].id : '';
        this.setData({
          shopList: list,
          shopIndex: Math.max(0, shopIdx),
          shopId: selectedShopId
        });
        if (selectedShopId) {
          wx.setStorageSync('adminShopId', selectedShopId);
        }
      })
      .catch(err => {
        console.error('加载管辖店铺列表失败', err);
      });
  },

  /**
   * 切换当前管理门店
   */
  onShopChange(e) {
    const idx = parseInt(e.detail.value, 10);
    const shop = this.data.shopList[idx];
    if (shop) {
      this.setData({
        shopIndex: idx,
        shopId: shop.id,
        selectedCategoryId: '',
        keyword: '',
        pageNum: 1
      }, () => {
        wx.setStorageSync('adminShopId', shop.id);
        this.loadCategories(shop.id);
        this.loadProducts(true);
      });
    }
  },

  /**
   * 获取当前店铺的所有分类
   */
  loadCategories(shopId) {
    const targetShopId = shopId || this.data.shopId;
    const params = {};
    if (targetShopId) {
      params.shopId = targetShopId;
    }
    return request.get('/category/admin/list', params, { silent: true })
      .then(res => {
        const rawList = Array.isArray(res) ? res : [];
        // 按 sortOrder 降序排序
        rawList.sort((a, b) => (b.sortOrder || 0) - (a.sortOrder || 0));

        // 校验当前选中的分类是否依然存在
        let currentCat = this.data.selectedCategoryId;
        if (currentCat && !rawList.some(c => String(c.id) === String(currentCat))) {
          currentCat = '';
        }

        this.setData({
          categories: rawList,
          selectedCategoryId: currentCat
        });
      })
      .catch(err => {
        console.error('加载店铺分类失败', err);
      });
  },

  /**
   * 点击切换分类
   */
  onSelectCategory(e) {
    const categoryId = e.currentTarget.dataset.id || '';
    if (this.data.selectedCategoryId === categoryId) return;

    this.setData({
      selectedCategoryId: categoryId,
      pageNum: 1,
      hasMore: true
    }, () => {
      this.loadProducts(true);
    });
  },

  /**
   * 跳转至分类管理
   */
  goToCategoryManager() {
    const shopId = this.data.shopId || '';
    wx.navigateTo({
      url: `/pages/merchant/category/index?shopId=${shopId}`
    });
  },

  onSearchInput(e) {
    this.setData({ keyword: e.detail.value });
  },

  onSearchConfirm() {
    this.loadProducts(true);
  },

  onClearSearch() {
    this.setData({ keyword: '' });
    this.loadProducts(true);
  },

  onSwitchStatus(e) {
    const status = e.currentTarget.dataset.status;
    this.setData({
      statusFilter: status === '' ? '' : parseInt(status, 10),
      pageNum: 1,
      hasMore: true
    });
    this.loadProducts(true);
  },

  loadProducts(refresh = false) {
    if (this.data.loading) return;
    const page = refresh ? 1 : this.data.pageNum;
    this.setData({ loading: true });

    const params = {
      pageNum: page,
      pageSize: this.data.pageSize
    };
    if (this.data.shopId) {
      params.shopId = this.data.shopId;
    }
    if (this.data.selectedCategoryId) {
      params.categoryId = this.data.selectedCategoryId;
    }
    if (this.data.keyword) {
      params.keyword = this.data.keyword.trim();
    }
    if (this.data.statusFilter !== '') {
      params.isOnSale = this.data.statusFilter;
    }

    request.get('/product/admin/list', params)
      .then(res => {
        const rawList = (res && res.records) || (res && res.list) || [];
        const total = res && res.total !== undefined ? res.total : rawList.length;
        const list = rawList.map(item => ({
          ...item,
          coverImage: formatImageUrl(item.coverImage)
        }));
        const productList = refresh ? list : this.data.productList.concat(list);
        this.setData({
          productList: productList,
          pageNum: page + 1,
          hasMore: productList.length < total,
          loading: false
        });
      })
      .catch(err => {
        console.error('获取掌柜商品列表失败', err);
        this.setData({ loading: false });
      });
  },

  onLoadMore() {
    if (this.data.hasMore && !this.data.loading) {
      this.loadProducts(false);
    }
  },

  goToAdd() {
    const shopId = this.data.shopId || '';
    const categoryId = this.data.selectedCategoryId || '';
    wx.navigateTo({
      url: `/pages/merchant/products/edit/index?shopId=${shopId}&categoryId=${categoryId}`
    });
  },

  goToEdit(e) {
    const id = e.currentTarget.dataset.id;
    const shopId = this.data.shopId || '';
    wx.navigateTo({
      url: `/pages/merchant/products/edit/index?id=${id}&shopId=${shopId}`
    });
  },

  // 快捷修改库存
  onQuickEditStock(e) {
    const id = e.currentTarget.dataset.id;
    const currentStock = e.currentTarget.dataset.stock;

    wx.showModal({
      title: '修改库存',
      editable: true,
      placeholderText: `当前库存: ${currentStock}`,
      success: (res) => {
        if (res.confirm) {
          const val = parseInt(res.content, 10);
          if (isNaN(val) || val < 0) {
            wx.showToast({ title: '请输入合法非负库存数字', icon: 'none' });
            return;
          }
          wx.showLoading({ title: '保存中...' });
          request.post('/product/admin/stock', { id: id, stock: val })
            .then(() => {
              wx.showToast({ title: '库存已更新', icon: 'success' });
              this.loadProducts(true);
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

  // 快捷切换上下架
  onToggleSale(e) {
    const id = e.currentTarget.dataset.id;
    const isOnSale = e.detail.value ? 1 : 0;

    wx.showLoading({ title: isOnSale === 1 ? '上架中...' : '下架中...' });
    request.post('/product/admin/status', { id: id, isOnSale: isOnSale })
      .then(() => {
        wx.showToast({ title: isOnSale === 1 ? '已成功上架' : '已成功下架', icon: 'success' });
        // 本地更新状态避免全量刷新跳动
        const list = this.data.productList.map(item => {
          if (item.id === id) {
            return { ...item, isOnSale: isOnSale };
          }
          return item;
        });
        this.setData({ productList: list });
      })
      .catch(err => {
        wx.showToast({ title: err.message || '操作失败', icon: 'none' });
        this.loadProducts(true);
      })
      .finally(() => {
        wx.hideLoading();
      });
  },

  // 删除商品
  onDeleteProduct(e) {
    const id = e.currentTarget.dataset.id;
    const name = e.currentTarget.dataset.name;

    wx.showModal({
      title: '删除确认',
      content: `确定要彻底删除商品【${name}】吗？删除后不可恢复。`,
      confirmColor: '#FF4757',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '删除中...' });
          request.del(`/product/admin/${id}`)
            .then(() => {
              wx.showToast({ title: '删除成功', icon: 'success' });
              this.loadProducts(true);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '删除失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  }
});
