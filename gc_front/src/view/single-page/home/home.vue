<template>
  <div>
    <Row type="flex" justify="start" :gutter="10" >
      <i-col :xs="12" :md="8" :lg="6" style="padding-top:5px;">
        <Card :key="selector._id" :padding="2"  shadown>
          <span class="tag">{{selector.title}}</span>
          <Divider type="vertical" />
          <span class="tag_value">{{ selector.dp_no}}</span>
        </Card>
      </i-col>
      <i-col :xs="12" :md="8" :lg="6" v-for="(infor, i) in inforCardData" :key="`infor-${i}`" style="padding-top:5px;">
        <Card :key="i" :padding="2" shadown>
          <span class="tag">{{infor.title}}</span>
          <Divider type="vertical" />
          <span class="tag_value">
            <strong>{{ infor.text}}</strong>
          </span>
        </Card>
      </i-col>
    </Row>
    <Divider dashed />
    <Row>
      <Table :columns="columns" :data="rows">
        <template slot-scope="{ row }" slot="name">
          <strong>{{ row.name }}</strong>
        </template>
        <template slot-scope="{ row, index }" slot="action">
          <Button
            type="primary"
            size="small"
            style="margin-right: 5px"
            @click="showPdfReport(index)"
          >報表</Button>
        </template>
      </Table>
    </Row>
  </div>
</template>
<style scoped>
.tag {
  font-size: 20px;
  background-color: #ffbf00;
}

.tag_value {
  font-size: 20px;
}

.verticalLine {
  border-left: thick solid #ff0000;
}
</style>
<script>
import InforCard from "_c/info-card";
import { ChartPie, ChartBar } from "_c/charts";
import config from "@/config";
import moment from "moment";

import { getRealtimeData, getCurrentMonitor, getLast10Data } from "@/api/data";
const baseUrl =
  process.env.NODE_ENV === "development"
    ? config.baseUrl.dev
    : config.baseUrl.pro;
export default {
  name: "home",
  components: {
    InforCard
  },
  data() {
    return {
      selector: {
        _id: "default",
        dp_no: "#2",
        icon: "ios-speedometer",
        color: "#ff0000",
        title: "選樣器"
      },
      inforCardData: [],
      timer: undefined,
      columns: [],
      rows: []
    };
  },
  mounted() {
    this.reloadData();
  },
  methods: {
    reloadData() {
      getCurrentMonitor()
        .then(resp => {
          this.selector = Object.assign(
            {
              icon: "ios-speedometer",
              color: "#ff0000",
              title: "選樣器"
            },
            resp.data
          );
        })
        .catch(err => {
          alert(err);
        });

      getRealtimeData()
        .then(resp => {
          this.inforCardData.splice(0, this.inforCardData.length);
          let card = {
            title: "資料時間",
            icon: "ios-time",
            text: moment(resp.data.time).format("M-D HH:mm"),
            color: "#ff9900"
          };
          this.inforCardData.push(card);
          for (let mtData of resp.data.mtDataList) {
            let card = {
              title: mtData.mtName,
              icon: "ios-flask",
              text: mtData.text,
              color: "#ff9900"
            };
            this.inforCardData.push(card);
          }
          this.pieKey++;
        })
        .catch(err => {
          alert(err);
        });

      getLast10Data()
        .then(resp => {
          const ret = resp.data;
          this.columns.splice(0, this.columns.length);
          this.rows.splice(0, this.rows.length);
          this.columns.push({
            title: "日期",
            key: "date",
            sortable: true
          });
          for (let i = 0; i < ret.columnNames.length; i++) {
            let col = {
              title: ret.columnNames[i],
              key: `col${i}`,
              sortable: true
            };
            this.columns.push(col);
          }
          // setup for report column
          this.columns.push({
            title: "動作",
            slot: "action",
            width: 150,
            align: "center"
          });
          for (let row of ret.rows) {
            let rowData = {
              date: new moment(row.date).format("lll"),
              cellClassName: {}
            };
            for (let c = 0; c < row.cellData.length; c++) {
              let key = `col${c}`;
              rowData[key] = row.cellData[c].v;
              rowData.cellClassName[key] = row.cellData[c].cellClassName;
              if (baseUrl.length !== 0) {
                rowData.pdfUrl = `${baseUrl}pdfReport/${row.pdfReport}`;
              } else {
                rowData.pdfUrl = `pdfReport/${row.pdfReport}`;
              }
            }
            this.rows.push(rowData);
          }
        })
        .catch(err => {
          alert(err);
        });
      this.timer = setTimeout(this.reloadData, 30000);
    },
    showPdfReport(idx) {
      let url = this.rows[idx].pdfUrl;
      window.open(url);
    }
  },
  destroyed() {
    clearTimeout(this.timer);
  }
};
</script>

<style lang="less">
.count-style {
  font-size: 50px;
}
</style>
