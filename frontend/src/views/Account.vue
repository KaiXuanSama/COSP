<script setup lang="ts">
import { ref } from 'vue'
import axios from 'axios'

const newUsername = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const saveMsg = ref('')
const saveError = ref('')

async function handleSubmit() {
  saveMsg.value = ''
  saveError.value = ''

  if (newPassword.value && newPassword.value !== confirmPassword.value) {
    saveError.value = '两次输入的新密码不一致。'
    return
  }

  if (newPassword.value && newPassword.value.length < 4) {
    saveError.value = '新密码长度至少 4 位。'
    return
  }

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
      saveMsg.value = data.message || '修改成功！'
      currentPassword.value = ''
      newPassword.value = ''
      confirmPassword.value = ''
      if (data.usernameChanged) {
        // 用户名变更，稍后跳转到登录页
        setTimeout(() => { window.location.href = '/login' }, 2000)
      }
    } else {
      saveError.value = data.error || '保存失败，请检查输入。'
    }
  } catch (e: any) {
    saveError.value = '网络错误，请稍后重试。'
  }
}
</script>

<template>
  <div class="account-page">
    <div v-if="saveMsg" class="message success">{{ saveMsg }}</div>
    <div v-if="saveError" class="message error">{{ saveError }}</div>

    <form class="settings-form" @submit.prevent="handleSubmit">
      <!-- 用户名修改 -->
      <div class="card">
        <div class="card-title">修改用户名</div>
        <div class="card-body">
          <div class="field-group">
            <label class="field-label" for="newUsername">新用户名</label>
            <div class="field-input-wrap">
              <input class="field-input" id="newUsername" v-model="newUsername" type="text" placeholder="请输入新用户名">
            </div>
          </div>
        </div>
      </div>

      <!-- 密码修改 -->
      <div class="card" style="margin-top: 16px;">
        <div class="card-title">修改密码</div>
        <div class="card-body">
          <div class="field-group">
            <label class="field-label" for="currentPassword">当前密码</label>
            <div class="field-input-wrap">
              <input class="field-input" id="currentPassword" v-model="currentPassword" type="password" placeholder="请输入当前密码">
            </div>
          </div>
          <div class="field-group">
            <label class="field-label" for="newPassword">新密码</label>
            <div class="field-input-wrap">
              <input class="field-input" id="newPassword" v-model="newPassword" type="password" placeholder="请输入新密码（至少4位）">
            </div>
          </div>
          <div class="field-group">
            <label class="field-label" for="confirmPassword">确认新密码</label>
            <div class="field-input-wrap">
              <input class="field-input" id="confirmPassword" v-model="confirmPassword" type="password" placeholder="请再次输入新密码">
            </div>
          </div>
        </div>
      </div>

      <div class="form-actions">
        <button class="submit-btn" type="submit">
          <span>保存修改</span>
        </button>
      </div>
    </form>
  </div>
</template>