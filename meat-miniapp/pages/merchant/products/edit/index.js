// pages/merchant/products/edit/index.js
const request = require('../../../../utils/request');
const auth = require('../../../../utils/auth');
const { formatImageUrl, toRelativePath } = require('../../../../utils/util');

Page({
  data: {
    isEdit: false,
    id: null,
    shopId: '',
    shopList: [],
    shopIndex: -1,
    categories: [],
    categoryIndex: -1,
    initialCategoryId: '',
    product: {
      shopId: '',
      categoryId: null,
      name: '',
      coverImage: '',
      displayCoverImage: '',
      price: '',
      originalPrice: '',
      unit: '斤',
      stock: 999,
      sortOrder: 0,
      summary: '',
      description: '',
      isOnSale: 1
    },
    specsList: [
      { name: '整块不切' },
      { name: '切薄片' },
      { name: '绞肉馅' },
      { name: '切小块/肉丁' }
    ],
    submitting: false
  },

  onLoad(options) {
    if (!auth.isAdmin()) {
      wx.showToast({ title: '无掌柜权限', icon: 'none' });
      setTimeout(() => {
        wx.switchTab({ url: '/pages/mine/mine' });
      }, 1000);
      return;
    }

    const id = options && options.id;
    const initialShopId = (options && options.shopId) || wx.getStorageSync('adminShopId') || '';
    const initialCategoryId = (options && options.categoryId) || '';

    if (id) {
      this.setData({ isEdit: true, id: id, shopId: initialShopId, initialCategoryId });
      wx.setNavigationBarTitle({ title: '编辑鲜肉商品' });
    } else {
      this.setData({ shopId: initialShopId, initialCategoryId });
      wx.setNavigationBarTitle({ title: '新增鲜肉商品' });
    }

    this.loadShops(initialShopId).then(() => {
      if (id) {
        this.loadDetail(id);
      } else {
        this.loadCategories(this.data.shopId, initialCategoryId);
      }
    });
  },

  onShow() {
    // 若从分类管理页新增分类后返回，重新刷新当前店铺分类
    if (this.data.shopId && !this.data.id) {
      this.loadCategories(this.data.shopId, this.data.product.categoryId || this.data.initialCategoryId);
    }
  },

  // 获取管理员管辖的商铺列表
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
          shopIndex: shopIdx,
          shopId: selectedShopId,
          'product.shopId': selectedShopId
        });
      })
      .catch(err => {
        console.error('加载店铺失败', err);
      });
  },

  onShopChange(e) {
    const idx = parseInt(e.detail.value, 10);
    const shop = this.data.shopList[idx];
    if (shop) {
      this.setData({
        shopIndex: idx,
        shopId: shop.id,
        'product.shopId': shop.id,
        'product.categoryId': null,
        categoryIndex: -1
      }, () => {
        this.loadCategories(shop.id);
      });
    }
  },

  // 获取指定商铺的分类列表（管理端包含所有分类并按权重降序）
  loadCategories(shopId, defaultCategoryId) {
    const targetShopId = shopId || this.data.shopId;
    const params = {};
    if (targetShopId) {
      params.shopId = targetShopId;
    }
    return request.get('/category/admin/list', params, { silent: true })
      .then(res => {
        const list = Array.isArray(res) ? res : [];
        list.sort((a, b) => (b.sortOrder || 0) - (a.sortOrder || 0));

        let catIdx = -1;
        const targetCatId = defaultCategoryId || this.data.product.categoryId;
        if (targetCatId) {
          catIdx = list.findIndex(c => String(c.id) === String(targetCatId));
        }

        const chosenCatId = catIdx >= 0 ? list[catIdx].id : null;

        this.setData({
          categories: list,
          categoryIndex: catIdx,
          'product.categoryId': chosenCatId
        });
      })
      .catch(err => {
        console.error('加载分类失败', err);
      });
  },

  // 跳转分类管理页
  goToManageCategory() {
    const shopId = this.data.shopId || '';
    wx.navigateTo({
      url: `/pages/merchant/category/index?shopId=${shopId}`
    });
  },

  // 加载商品详情回显
  loadDetail(id) {
    wx.showLoading({ title: '加载中...' });
    request.get(`/product/detail/${id}`)
      .then(res => {
        let specs = [];
        if (res.specsJson) {
          try {
            const parsed = JSON.parse(res.specsJson);
            if (Array.isArray(parsed)) {
              specs = parsed.map(item => (typeof item === 'string' ? { name: item } : item));
            }
          } catch (e) {
            console.error('解析规格失败', e);
          }
        }

        const effectiveShopId = res.shopId || this.data.shopId;
        let shopIdx = this.data.shopIndex;
        if (effectiveShopId && this.data.shopList.length > 0) {
          const found = this.data.shopList.findIndex(s => s.id === effectiveShopId);
          if (found >= 0) shopIdx = found;
        }

        // 加载商品所属店铺分类并回显索引
        this.loadCategories(effectiveShopId, res.categoryId).then(() => {
          const catIdx = this.data.categories.findIndex(c => String(c.id) === String(res.categoryId));
          this.setData({
            categoryIndex: catIdx,
            'product.categoryId': res.categoryId
          });
        });

        this.setData({
          product: {
            id: res.id,
            shopId: effectiveShopId,
            categoryId: res.categoryId,
            name: res.name || '',
            coverImage: toRelativePath(res.coverImage),
            displayCoverImage: formatImageUrl(res.coverImage),
            price: res.price,
            originalPrice: res.originalPrice || '',
            unit: res.unit || '斤',
            stock: res.stock !== undefined ? res.stock : 999,
            sortOrder: res.sortOrder || 0,
            summary: res.summary || '',
            description: res.description || '',
            isOnSale: res.isOnSale !== undefined ? res.isOnSale : 1
          },
          shopIndex: shopIdx,
          shopId: effectiveShopId,
          specsList: specs
        });
      })
      .catch(err => {
        wx.showToast({ title: err.message || '获取商品失败', icon: 'none' });
      })
      .finally(() => {
        wx.hideLoading();
      });
  },

  onCategoryChange(e) {
    const idx = parseInt(e.detail.value, 10);
    const cat = this.data.categories[idx];
    this.setData({
      categoryIndex: idx,
      'product.categoryId': cat ? cat.id : null
    });
  },

  // 选择图片来源：拍照 / 相册
  chooseAndUploadImage() {
    wx.showActionSheet({
      itemList: ['拍照上传', '从相册选择'],
      success: (res) => {
        const sourceType = res.tapIndex === 0 ? 'camera' : 'album';
        this.pickAndUploadImage(sourceType);
      },
      fail: () => {
        // 用户取消选择
      }
    });
  },

  // 按指定来源选择并上传图片
  pickAndUploadImage(sourceType) {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: [sourceType],
      sizeType: ['compressed'],
      camera: 'back',
      success: (res) => {
        const tempFilePath = res.tempFiles[0].tempFilePath;
        wx.showLoading({ title: '上传图片中...' });
        request.uploadFile({
          filePath: tempFilePath,
          formData: { module: 'product' }
        })
          .then(data => {
            wx.showToast({ title: '上传成功', icon: 'success' });
            const relPath = toRelativePath(data.url, data.relativePath || data.relativeUrl);
            const dispUrl = formatImageUrl(relPath);
            this.setData({
              'product.coverImage': relPath,
              'product.displayCoverImage': dispUrl
            });
          })
          .catch(err => {
            console.error('上传图片失败', err);
          })
          .finally(() => {
            wx.hideLoading();
          });
      }
    });
  },

  previewCover() {
    const img = this.data.product.displayCoverImage || formatImageUrl(this.data.product.coverImage);
    if (img) {
      wx.previewImage({
        urls: [img]
      });
    }
  },

  removeCover() {
    this.setData({
      'product.coverImage': '',
      'product.displayCoverImage': ''
    });
  },

  // 添加切法规格
  onAddSpec() {
    wx.showModal({
      title: '添加切法规格',
      editable: true,
      placeholderText: '如：切薄片、包饺子肉馅、剁大块',
      success: (res) => {
        if (res.confirm && res.content) {
          const name = res.content.trim();
          if (name) {
            const list = this.data.specsList.concat([{ name: name }]);
            this.setData({ specsList: list });
          }
        }
      }
    });
  },

  onRemoveSpec(e) {
    const idx = e.currentTarget.dataset.index;
    const list = this.data.specsList.filter((_, i) => i !== idx);
    this.setData({ specsList: list });
  },

  onSaleChange(e) {
    this.setData({
      'product.isOnSale': e.detail.value ? 1 : 0
    });
  },

  // 提交保存
  onSubmitForm(e) {
    if (this.data.submitting) return;

    const values = e.detail.value;
    const { categoryId, coverImage, isOnSale } = this.data.product;

    if (!this.data.shopId) {
      wx.showToast({ title: '请选择所属门店', icon: 'none' });
      return;
    }
    if (!categoryId) {
      wx.showToast({ title: '请选择所属分类', icon: 'none' });
      return;
    }
    if (!values.name || !values.name.trim()) {
      wx.showToast({ title: '请输入商品名称', icon: 'none' });
      return;
    }
    if (!coverImage) {
      wx.showToast({ title: '请上传商品主图', icon: 'none' });
      return;
    }
    if (!values.price || isNaN(parseFloat(values.price)) || parseFloat(values.price) <= 0) {
      wx.showToast({ title: '请输入合法的销售单价', icon: 'none' });
      return;
    }

    const payload = {
      id: this.data.id || null,
      shopId: this.data.shopId,
      categoryId: categoryId,
      name: values.name.trim(),
      coverImage: coverImage,
      price: parseFloat(values.price),
      originalPrice: values.originalPrice ? parseFloat(values.originalPrice) : null,
      unit: values.unit ? values.unit.trim() : '斤',
      stock: values.stock ? parseInt(values.stock, 10) : 999,
      sortOrder: values.sortOrder ? parseInt(values.sortOrder, 10) : 0,
      summary: values.summary ? values.summary.trim() : '',
      description: values.description ? values.description.trim() : '',
      isOnSale: isOnSale,
      specsJson: JSON.stringify(this.data.specsList)
    };

    this.setData({ submitting: true });
    wx.showLoading({ title: '保存中...' });

    request.post('/product/admin/save', payload)
      .then(() => {
        wx.showToast({ title: '保存成功', icon: 'success' });
        setTimeout(() => {
          wx.navigateBack();
        }, 1200);
      })
      .catch(err => {
        wx.showToast({ title: err.message || '保存失败', icon: 'none' });
      })
      .finally(() => {
        this.setData({ submitting: false });
        wx.hideLoading();
      });
  },

  // 详情页删除商品
  onDeleteCurrentProduct() {
    const id = this.data.id;
    const name = this.data.product.name;
    if (!id) return;

    wx.showModal({
      title: '删除确认',
      content: `确定要彻底删除鲜肉商品【${name || '该商品'}】吗？删除后不可恢复。`,
      confirmColor: '#FF4757',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '删除中...' });
          request.del(`/product/admin/${id}`)
            .then(() => {
              wx.showToast({ title: '删除成功', icon: 'success' });
              setTimeout(() => {
                wx.navigateBack();
              }, 1200);
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
