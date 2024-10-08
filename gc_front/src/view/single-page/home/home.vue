<template>
  <div>
    <Row type="flex" justify="start" :gutter="10" v-show="showGcSelector">
      <Col :xs="12" :md="8" :lg="6" style="padding-top: 5px">
        <ButtonGroup title="GC切換顯示">
          <Button
            class="btnStyle"
            v-for="gc in gcList"
            :key="gc.key"
            :type="buttonType(gc.key)"
            :icon="buttonIcon(gc.key)"
            @click="
              gcFilter = gc.key;
              reloadData();
            "
          >
            <h3>{{ gc.name }}</h3>
          </Button>
        </ButtonGroup>
      </Col>
    </Row>
    <Row type="flex" justify="start" :gutter="10">
      <Col :xs="12" :md="8" :lg="6" style="padding-top: 5px">
        <Card :key="selector._id" :padding="2" shadow class="tag">
          <Row type="flex">
            <Col span="8">
              <strong>{{ selector.title }}</strong>
            </Col>
            <Col span="16" class="tag_value">{{ selector.dp_no }}</Col>
          </Row>
        </Card>
      </Col>
      <Col
        :xs="12"
        :md="8"
        :lg="6"
        v-for="(infor, i) in inforCardData"
        :key="`infor-${i}`"
        style="padding-top: 5px"
      >
        <Card :key="i" :padding="2" shadow class="tag">
          <Row type="flex">
            <Col span="8">
              <strong>{{ infor.title }}</strong>
            </Col>
            <Col span="16" class="tag_value">{{ infor.text }}</Col>
          </Row>
        </Card>
      </Col>
    </Row>
    <Divider dashed />
    <Row>
      <Table :columns="columns" :data="rows">
        <template slot-scope="{ row }" slot="name">
          <strong>{{ row.name }}</strong>
        </template>
        <template slot-scope="{ row, index }" slot="action">
          <table>
            <tbody>
              <tr>
                <td>
                  <Button
                    type="primary"
                    size="large"
                    @click="showPdfReport(index)"
                    >PDF</Button
                  >
                </td>
                <td>
                  <!-- <Button type="info" size="large" @click="downloadForm(index)"
                    >報告</Button
                  > -->
                </td>
              </tr>
              <tr></tr>
            </tbody>
          </table>
        </template>
      </Table>
    </Row>
  </div>
</template>
<style scoped>
.tag {
  text-align: center;
  padding: 3px;
  font-size: 20px;
  background-color: #ffbf00;
}

.tag_value {
  text-align: center;
  border-radius: 4px;
  font-size: 20px;
  background-color: white;
}

.verticalLine {
  border-left: thick solid #ff0000;
}
</style>
<script>
import InforCard from '_c/info-card';
import config from '@/config';
import moment from 'moment';

import { getGcList, getRealtimeData, getLast10Data } from '@/api/data';
const baseUrl =
  process.env.NODE_ENV === 'development'
    ? config.baseUrl.dev
    : config.baseUrl.pro;
export default {
  name: 'home',
  components: {
    InforCard,
  },
  data() {
    return {
      selector: {
        _id: 'default',
        dp_no: '#2',
        icon: 'ios-speedometer',
        color: '#ff0000',
        title: 'GC/選樣器',
      },
      inforCardData: [],
      timer: undefined,
      columns: [],
      rows: [],
      gcList: [],
      gcFilter: '',
    };
  },
  async mounted() {
    getGcList()
      .then(resp => {
        const ret = resp.data;
        this.gcList.splice(0, this.gcList.length);
        for (let gc of ret) {
          this.gcList.push(gc);
        }
        if (this.gcList.length !== 0) this.gcFilter = this.gcList[0].key;
        this.reloadData();
      })
      .catch(err => alert(err));
  },
  computed: {
    showGcSelector() {
      return this.gcList.length > 1;
    },
  },
  methods: {
    buttonType(id) {
      if (this.gcFilter === id) return 'success';
      else return 'default';
    },
    buttonIcon(id) {
      if (this.gcFilter === id) {
        return 'md-checkbox-outline';
      }

      return 'md-square-outline';
    },
    reloadData() {
      getRealtimeData(this.gcFilter).then(resp => {
        const ret = resp.data;
        this.inforCardData.splice(0, this.inforCardData.length);
        let card = {
          title: '資料時間',
          icon: 'ios-time',
          text: moment(ret.time).format('M-D HH:mm'),
          color: '#ff9900',
        };
        this.inforCardData.push(card);
        let executeCountCard = {
          title: '執行次數',
          icon: 'ios-stats',
          text: ret.executeCount,
          color: '#ff9900',
        };
        this.inforCardData.push(executeCountCard);

        for (let mtData of ret.mtDataList) {
          let card = {
            title: mtData.mtName,
            icon: 'ios-flask',
            text: mtData.text,
            color: '#ff9900',
          };
          this.inforCardData.push(card);
        }
        this.selector.dp_no = ret.monitor;
      });

      getLast10Data(this.gcFilter).then(resp => {
        const ret = resp.data;
        this.columns.splice(0, this.columns.length);
        this.rows.splice(0, this.rows.length);
        this.columns.push({
          title: '日期',
          key: 'date',
          sortable: true,
        });
        for (let i = 0; i < ret.columnNames.length; i++) {
          let col = {
            title: ret.columnNames[i],
            key: `col${i}`,
            sortable: true,
          };
          this.columns.push(col);
        }
        // setup for report column
        this.columns.push({
          title: '動作',
          slot: 'action',
          align: 'center',
        });
        for (let row of ret.rows) {
          let rowData = {
            date: new moment(row.date).format('lll'),
            cellClassName: {},
          };
          if (baseUrl.length !== 0) {
            rowData.pdfUrl = `${baseUrl}pdfReport/${row.pdfReport}`;
            rowData.excelUrl = `${baseUrl}excelForm/${row.pdfReport}`;
          } else {
            rowData.pdfUrl = `pdfReport/${row.pdfReport}`;
            rowData.excelUrl = `${baseUrl}excelForm/${row.pdfReport}`;
          }

          for (let c = 0; c < row.cellData.length; c++) {
            let key = `col${c}`;
            rowData[key] = row.cellData[c].v;
            rowData.cellClassName[key] = row.cellData[c].cellClassName;
          }
          this.rows.push(rowData);
        }
      });

      this.timer = setTimeout(this.reloadData, 30000);
    },
    showPdfReport(idx) {
      let url = this.rows[idx].pdfUrl;
      window.open(url);
    },
    downloadForm(idx) {
      let url = this.rows[idx].excelUrl;
      window.open(url);
    },
  },
  destroyed() {
    clearTimeout(this.timer);
  },
};
</script>

<style lang="less">
.count-style {
  font-size: 50px;
}
</style>
