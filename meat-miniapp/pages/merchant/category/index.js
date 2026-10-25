// pages/merchant/category/index.js - 分类管理 (门店专属独立分类)
const request = require('../../../utils/request');
const auth = require('../../../utils/auth');

Page({
  data: {
    shopList: [],          // 管理员管辖的店铺列表
    shopIndex: 0,          // 当前选中的店铺索引
    currentShopId: '',     // 当前选中的店铺ID

    categoryList: [],      // 完整分类列表（按权重从大到小排列）
    filteredList: [],      // 过滤与搜索后的分类列表
    activeCount: 0,        // 启用中的数量
    disabledCount: 0,      // 已停用的数量
    statusFilter: 'all',   // 状态筛选: 'all' | 'active' | 'disabled'
    searchKeyword: '',     // 搜索关键词
    loading: false,

    // 编辑/新增弹窗相关
    showModal: false,
    isEdit: false,
    formData: {
      id: null,
      shopId: '',          // 分类所属店铺ID
      name: '',
      icon: '',            // 允许为空，留空时不展示图标
      sortOrder: 10,
      status: 1
    },
    submitting: false,

    // 预设萌系常用 Emoji
    presetIcons: ['🥩', '🍖', '🐖', '🐂', '🥓', '🍗', '🍲', '🥟']
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
    this.loadShops(initialShopId);
  },

  onPullDownRefresh() {
    this.loadCategories(this.data.currentShopId);
  },

  /**
   * 加载当前管理员管辖的店铺列表
   */
  loadShops(preferShopId) {
    return request.get('/shop/admin/my-shops', {}, { silent: true })
      .then(res => {
        const list = Array.isArray(res) ? res : [];
        let shopIdx = -1;
        const targetId = preferShopId || wx.getStorageSync('adminShopId');
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
          currentShopId: selectedShopId
        }, () => {
          if (selectedShopId) {
            this.loadCategories(selectedShopId);
          }
        });
      })
      .catch(err => {
        console.error('加载管辖店铺列表失败', err);
        this.loadCategories('');
      });
  },

  /**
   * 切换当前管理的店铺
   */
  onShopChange(e) {
    const idx = parseInt(e.detail.value, 10);
    const shop = this.data.shopList[idx];
    if (shop) {
      this.setData({
        shopIndex: idx,
        currentShopId: shop.id
      }, () => {
        wx.setStorageSync('adminShopId', shop.id);
        this.loadCategories(shop.id);
      });
    }
  },

  /**
   * 加载指定店铺的分类列表并统计数据
   */
  loadCategories(shopId) {
    this.setData({ loading: true });
    const targetShopId = shopId || this.data.currentShopId;
    const params = {};
    if (targetShopId) {
      params.shopId = targetShopId;
    }

    request.get('/category/admin/list', params)
      .then(res => {
        const list = Array.isArray(res) ? res : [];
        // 确保默认按 sortOrder 从大到小降序排列
        list.sort((a, b) => (b.sortOrder || 0) - (a.sortOrder || 0));

        let activeCount = 0;
        let disabledCount = 0;
        list.forEach(item => {
          if (item.status === 1) {
            activeCount++;
          } else {
            disabledCount++;
          }
        });

        this.setData({
          categoryList: list,
          activeCount,
          disabledCount,
          loading: false
        }, () => {
          this.applyFilter();
        });
      })
      .catch(err => {
        console.error('获取分类列表失败', err);
        wx.showToast({ title: '加载失败，请重试', icon: 'none' });
        this.setData({ loading: false });
      })
      .finally(() => {
        wx.stopPullDownRefresh();
      });
  },

  /**
   * 应用搜索与状态筛选
   */
  applyFilter() {
    const { categoryList, statusFilter, searchKeyword } = this.data;
    const kw = (searchKeyword || '').trim().toLowerCase();

    let list = categoryList.filter(item => {
      // 状态筛选
      if (statusFilter === 'active' && item.status !== 1) return false;
      if (statusFilter === 'disabled' && item.status !== 0) return false;

      // 关键词搜索
      if (kw) {
        const name = (item.name || '').toLowerCase();
        return name.includes(kw);
      }
      return true;
    });

    this.setData({ filteredList: list });
  },

  /**
   * 搜索输入
   */
  onSearchInput(e) {
    const val = e.detail.value || '';
    this.setData({ searchKeyword: val }, () => {
      this.applyFilter();
    });
  },

  /**
   * 清空搜索
   */
  onClearSearch() {
    this.setData({ searchKeyword: '' }, () => {
      this.applyFilter();
    });
  },

  /**
   * 切换状态筛选 Tab
   */
  onSwitchFilter(e) {
    const filter = e.currentTarget.dataset.filter;
    if (this.data.statusFilter === filter) return;
    this.setData({ statusFilter: filter }, () => {
      this.applyFilter();
    });
  },

  /**
   * 列表快捷切换启用/停用状态
   */
  onQuickToggleStatus(e) {
    const item = e.currentTarget.dataset.item;
    const newStatus = e.detail.value ? 1 : 0;

    const payload = {
      id: item.id,
      shopId: item.shopId || this.data.currentShopId,
      name: item.name,
      icon: item.icon ? item.icon.trim() : '',
      sortOrder: item.sortOrder || 0,
      status: newStatus
    };

    wx.showLoading({ title: '更新状态中...', mask: true });
    request.post('/category/admin/save', payload)
      .then(() => {
        wx.showToast({
          title: newStatus === 1 ? `已启用「${item.name}」` : `已停用「${item.name}」`,
          icon: 'success'
        });
        this.loadCategories(this.data.currentShopId);
      })
      .catch(err => {
        wx.showToast({ title: err.message || '更新状态失败', icon: 'none' });
        this.loadCategories(this.data.currentShopId);
      })
      .finally(() => {
        wx.hideLoading();
      });
  },

  /**
   * 快捷置顶分类
   */
  onQuickTopCategory(e) {
    const item = e.currentTarget.dataset.item;
    const { categoryList } = this.data;

    let maxSort = 0;
    categoryList.forEach(c => {
      const s = Number(c.sortOrder) || 0;
      if (s > maxSort) maxSort = s;
    });
    const newSortOrder = maxSort + 10;

    wx.showModal({
      title: '置顶确认',
      content: `确定将分类【${item.name}】置顶吗？其权重将提升为 ${newSortOrder}。`,
      confirmColor: '#FF6B6B',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '置顶中...' });
          const payload = {
            id: item.id,
            shopId: item.shopId || this.data.currentShopId,
            name: item.name,
            icon: item.icon ? item.icon.trim() : '',
            sortOrder: newSortOrder,
            status: item.status !== undefined ? item.status : 1
          };

          request.post('/category/admin/save', payload)
            .then(() => {
              wx.showToast({ title: '已置顶', icon: 'success' });
              this.loadCategories(this.data.currentShopId);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '置顶失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  /**
   * 打开新增分类弹窗
   */
  openAddModal() {
    const { categoryList, currentShopId } = this.data;
    let maxSort = 0;
    categoryList.forEach(c => {
      const s = Number(c.sortOrder) || 0;
      if (s > maxSort) maxSort = s;
    });

    this.setData({
      showModal: true,
      isEdit: false,
      formData: {
        id: null,
        shopId: currentShopId, // 绑定当前门店
        name: '',
        icon: '', // 默认为空，图标允许为空
        sortOrder: maxSort + 10,
        status: 1
      }
    });
  },

  /**
   * 打开编辑分类弹窗
   */
  openEditModal(e) {
    const item = e.currentTarget.dataset.item;
    this.setData({
      showModal: true,
      isEdit: true,
      formData: {
        id: item.id,
        shopId: item.shopId || this.data.currentShopId, // 绑定店铺
        name: item.name || '',
        icon: item.icon ? item.icon.trim() : '', // 允许为空
        sortOrder: item.sortOrder !== undefined ? item.sortOrder : 0,
        status: item.status !== undefined ? item.status : 1
      }
    });
  },

  /**
   * 关闭弹窗
   */
  closeModal() {
    this.setData({ showModal: false });
  },

  /**
   * 阻止冒泡
   */
  stopBubble() {},

  /**
   * 表单输入
   */
  onInputName(e) {
    this.setData({ 'formData.name': e.detail.value });
  },

  onInputSort(e) {
    const val = parseInt(e.detail.value, 10);
    this.setData({ 'formData.sortOrder': isNaN(val) ? 0 : val });
  },

  onInputIcon(e) {
    this.setData({ 'formData.icon': e.detail.value });
  },

  /**
   * 选择预设 Emoji (点击已选中项可取消选中置空)
   */
  onSelectPresetIcon(e) {
    const icon = e.currentTarget.dataset.icon;
    const currentIcon = this.data.formData.icon;
    const newIcon = currentIcon === icon ? '' : icon;
    this.setData({ 'formData.icon': newIcon });
  },

  /**
   * 清空图标
   */
  onClearIcon() {
    this.setData({ 'formData.icon': '' });
  },

  onStatusChange(e) {
    this.setData({ 'formData.status': e.detail.value ? 1 : 0 });
  },

  /**
   * 提交保存分类 (绑定店铺，支持图标为空)
   */
  onSubmitCategory() {
    if (this.data.submitting) return;

    const { name, icon, sortOrder, status, id, shopId } = this.data.formData;
    if (!name || !name.trim()) {
      wx.showToast({ title: '请输入分类名称', icon: 'none' });
      return;
    }

    const targetShopId = shopId || this.data.currentShopId;
    if (!targetShopId) {
      wx.showToast({ title: '未找到归属店铺', icon: 'none' });
      return;
    }

    this.setData({ submitting: true });
    wx.showLoading({ title: '保存中...' });

    const payload = {
      id: id || null,
      shopId: targetShopId,
      name: name.trim(),
      icon: icon ? icon.trim() : '',
      sortOrder: Number(sortOrder) || 0,
      status: status
    };

    request.post('/category/admin/save', payload)
      .then(() => {
        wx.showToast({ title: '保存成功', icon: 'success' });
        this.closeModal();
        this.loadCategories(this.data.currentShopId);
      })
      .catch(err => {
        wx.showToast({ title: err.message || '保存失败', icon: 'none' });
      })
      .finally(() => {
        this.setData({ submitting: false });
        wx.hideLoading();
      });
  },

  /**
   * 删除分类
   */
  onDeleteCategory(e) {
    const { id, name, count } = e.currentTarget.dataset;
    const goodsCount = Number(count) || 0;

    // 若分类下已有商品，进行拦截并友好提示
    if (goodsCount > 0) {
      wx.showModal({
        title: '无法删除该分类',
        content: `分类【${name}】下还有 ${goodsCount} 件商品！请先在商品管理中将商品移至其它分类或下架删除，再来删除此分类。`,
        showCancel: false,
        confirmText: '我知道了',
        confirmColor: '#FF6B6B'
      });
      return;
    }

    wx.showModal({
      title: '删除确认',
      content: `确定要删除分类【${name}】吗？删除后不可恢复。`,
      confirmColor: '#FF4757',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '删除中...' });
          request.del(`/category/admin/${id}`)
            .then(() => {
              wx.showToast({ title: '已删除', icon: 'success' });
              this.loadCategories(this.data.currentShopId);
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
