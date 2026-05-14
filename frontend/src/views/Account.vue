<script setup lang="ts">
import { ref } from 'vue'
import { NCard, NInput, NButton, useMessage } from 'naive-ui'
import axios from 'axios'

const message = useMessage()
const newUsername = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const saving = ref(false)

async function handleSubmit() {
  if (newPassword.value && newPassword.value !== confirmPassword.value) {
    message.error('两次输入的新密码不一致。')
    return
  }

  if (newPassword.value && newPassword.value.length < 4) {
    message.error('新密码长度至少 4 位。')
    return
  }

  saving.value = true
  try {
    const formData = new URLSearchParams()
    if (newUsername.value) formData.append('newUsername', newUsername.value)
    if (currentPassword.value) formData.append('currentPassword', currentPassword.value)
    if (newPassword.value) formData.append('newPassword', newPassword.value)
    if (confirmPassword.value) formData.append('confirmPassword', confirmPassword.value)

    const res = await axios.post('/config/api/account', formData.toString(), {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
    const data = res.data
    if (data.ok) {
      message.success(data.message || '修改成功！')
      currentPassword.value = ''
      newPassword.value = ''
      confirmPassword.value = ''
      if (data.usernameChanged) {
        setTimeout(() => { window.location.href = '/login' }, 2000)
      }
    } else {
      message.error(data.error || '保存失败，请检查输入。')
    }
  } catch {
    message.error('网络错误，请稍后重试。')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="account-page">
    <form @submit.prevent="handleSubmit">
      <n-card title="修改用户名" :bordered="true">
        <div class="field-group">
          <label class="field-label" for="newUsername">新用户名</label>
          <n-input id="newUsername" v-model:value="newUsername" type="text" placeholder="请输入新用户名" />
        </div>
      </n-card>

      <n-card title="修改密码" :bordered="true" style="margin-top: 16px;">
        <div class="field-group">
          <label class="field-label" for="currentPassword">当前密码</label>
          <n-input id="currentPassword" v-model:value="currentPassword" type="password" placeholder="请输入当前密码" show-password-on="click" />
        </div>
        <div class="field-group">
          <label class="field-label" for="newPassword">新密码</label>
          <n-input id="newPassword" v-model:value="newPassword" type="password" placeholder="请输入新密码（至少4位）" show-password-on="click" />
        </div>
        <div class="field-group">
          <label class="field-label" for="confirmPassword">确认新密码</label>
          <n-input id="confirmPassword" v-model:value="confirmPassword" type="password" placeholder="请再次输入新密码" show-password-on="click" />
        </div>
      </n-card>

      <div class="form-actions">
        <n-button type="primary" attr-type="submit" :loading="saving" size="large">
          保存修改
        </n-button>
      </div>
    </form>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.field-group {
  margin-bottom: $space-md;
}

.field-label {
  display: block;
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: $text-muted;
  margin-bottom: 6px;
}

.form-actions {
  margin-top: $space-lg;
  display: flex;
  justify-content: flex-end;
}
</style>