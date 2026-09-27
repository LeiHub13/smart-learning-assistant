<script>
export default {
  onLaunch() {
    if (!uni.getStorageSync('la_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
    }
  }
}
</script>

<style>
page {
  background-color: #f5f6fa;
  color: #2b2f36;
  font-size: 15px;
}
</style>
