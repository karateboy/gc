<template>
  <Row>
    <Spin size="large" fix v-if="spinShow"></Spin>
    <Card v-for="(gc, idx) in gcList" :key="gc" :title="`${gc}切換通道`">
      <ButtonGroup shape="circle">
        <Button
          size="large"
          class="btnStyle"
          v-for="item in gcMonitorList[idx]"
          :key="item._id"
          :type="buttonType(item._id)"
          :icon="buttonIcon(item._id)"
          @click="setSelector(item._id)"
          :disabled="!localMode"
        >
          <h3>{{ `通道${item.selector}` }}</h3>
          {{ item.dp_no }}
        </Button>
      </ButtonGroup>
    </Card>
  </Row>
</template>
<style scoped>
.btnStyle {
  font-size: 18px;
  margin: 5px;
}
</style>
<script>
import InforCard from '_c/info-card';
import { mapState } from 'vuex';

import {
  getCurrentMonitor,
  setCurrentMonitor,
  getGcMonitorList,
} from '@/api/data';

export default {
  name: 'selector',
  components: {
    InforCard,
  },
  mounted() {
    getGcMonitorList()
      .then(resp => {
        const ret = resp.data;
        for (let gc in ret) {
          this.gcList.push(gc);
          this.gcMonitorList.push(ret[gc]);
        }
      })
      .catch(err => alert(err));

    this.refreshCurrentSelector();
  },
  data() {
    return {
      gcList: [],
      monitorList: [],
      gcMonitorList: [],
      current_selector: [],
      spinShow: false,
    };
  },
  computed: {
    ...mapState(['localMode']),
  },
  methods: {
    buttonType(id) {
      if (this.current_selector.indexOf(id) !== -1) return 'success';
      else return 'default';
    },
    buttonIcon(id) {
      if (this.current_selector.indexOf(id) !== -1) {
        return 'md-checkbox-outline';
      }

      return 'md-square-outline';
    },
    setSelector(current) {
      this.spinShow = true;
      setCurrentMonitor(current)
        .then(resp => {
          this.spinShow = false;
          this.$Message.success('切換成功');
          this.refreshCurrentSelector();
        })
        .catch(err => {
          this.spinShow = false;
          alert(err);
        });
    },
    refreshCurrentSelector() {
      getCurrentMonitor()
        .then(resp => {
          const ret = resp.data;
          this.current_selector.splice(0, this.current_selector.length);
          for (let selector of ret) {
            this.current_selector.push(selector._id);
          }
        })
        .catch(err => {
          alert(err);
        });
    },
  },
};
</script>
