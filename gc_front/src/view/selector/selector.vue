<template>
  <div>
    <Row>
      <Spin size="large" fix v-if="spinShow"></Spin>
      <Card title="切換通道">
        <ButtonGroup size="large">
          <Button
            size="large"
            v-for="item in monitorList"
            :key="item._id"
            :type="buttonType(item._id)"
            :icon="buttonIcon(item._id)"
            @click="setSelector(item._id)"
          >
            <p>{{'通道' + item._id}}</p>
            {{item.dp_no}}
          </Button>
        </ButtonGroup>
      </Card>
    </Row>
  </div>
</template>
<style scoped>
</style>
<script>
import InforCard from '_c/info-card';
import config from '@/config';

import { getMonitors, getCurrentMonitor, setCurrentMonitor } from '@/api/data';
const baseUrl =
  process.env.NODE_ENV === 'development'
    ? config.baseUrl.dev
    : config.baseUrl.pro;
export default {
  name: 'selector',
  components: {
    InforCard
  },
  mounted() {
    getMonitors()
      .then(resp => {
        this.monitorList.splice(0, this.monitorList.length);
        for (let mt of resp.data) {
          this.monitorList.push(mt);
        }
      })
      .catch(err => {
        alert(err);
      });
    this.refreshCurrentSelector();
  },
  data() {
    return {
      monitorList: [],
      selector: {
        _id: 'default',
        dp_no: '#2',
        icon: 'ios-speedometer',
        color: '#ff0000',
        title: '選樣器通道'
      },
      spinShow: false
    };
  },
  computed: {},
  methods: {
    buttonType(ch) {
      if (ch === this.selector._id) return 'success';
      else return 'default';
    },
    buttonIcon(ch) {
      if (ch === this.selector._id) return 'md-checkmark';
      else return '';
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
          this.selector = Object.assign(
            {
              icon: 'ios-speedometer',
              color: '#ff0000',
              title: '選樣器通道'
            },
            resp.data
          );
        })
        .catch(err => {
          alert(err);
        });
    }
  }
};
</script>
