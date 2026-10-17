#!/usr/bin/env python3
"""Cache real ECCC observations and evaluate a strictly chronological one-hour forecast.
No street-level CFD validation is claimed. stdlib only. Output is reproducible from raw cache.
"""
import argparse, csv, datetime as dt, json, math, pathlib, urllib.parse, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--days',type=int,default=90);p.add_argument('--offline',action='store_true');a=p.parse_args()
out=ROOT/'data/weather';out.mkdir(parents=True,exist_ok=True)
raw=out/'eccc-city-centre.json'
if not a.offline:
 end=dt.datetime.now(dt.timezone.utc);start=end-dt.timedelta(days=a.days)
 params={'f':'json','limit':10000,'STATION_NAME':'TORONTO CITY CENTRE','datetime':start.strftime('%Y-%m-%dT%H:%M:%SZ')+'/'+end.strftime('%Y-%m-%dT%H:%M:%SZ'),'sortby':'UTC_DATE'}
 url='https://api.weather.gc.ca/collections/climate-hourly/items?'+urllib.parse.urlencode(params)
 features=[];page=url
 while page:
  d=json.load(urllib.request.urlopen(page,timeout=90));features.extend(d['features']);page=next((x['href'] for x in d.get('links',[]) if x['rel']=='next'),None)
 raw.write_text(json.dumps({'source':url,'fetched_utc':end.isoformat(),'features':features},separators=(',',':')))
d=json.loads(raw.read_text());rows=[]
for f in d['features']:
 v=f['properties'];speed=v.get('WIND_SPEED');direction=v.get('WIND_DIRECTION')
 if speed is None or (direction is None and speed!=0) or v.get('WIND_SPEED_FLAG') not in (None,'') or v.get('WIND_DIRECTION_FLAG') not in (None,''):continue
 # Meteorological direction is where wind comes FROM, in tens of degrees.
 speed=float(speed)/3.6;deg=float(direction or 0)*10;r=math.radians(deg)
 rows.append({'utc':v['UTC_DATE']+'Z','speed_ms':speed,'from_deg':deg,'u_east':-speed*math.sin(r),'v_south':speed*math.cos(r)})
rows=sorted({r['utc']:r for r in rows}.values(),key=lambda x:x['utc'])
with (out/'observations.csv').open('w') as f:
 w=csv.DictWriter(f,fieldnames=['utc','speed_ms','from_deg','u_east','v_south']);w.writeheader();w.writerows(rows)
pairs=[(x,y) for x,y in zip(rows,rows[1:]) if (dt.datetime.fromisoformat(y['utc'].replace('Z','+00:00'))-dt.datetime.fromisoformat(x['utc'].replace('Z','+00:00'))).total_seconds()==3600]
cut=int(len(pairs)*.7);train,test=pairs[:cut],pairs[cut:]
if not train or not test:raise SystemExit('Not enough consecutive observations for train/test')
# Fit vector AR(1) separately by axis, with intercept, using training rows only.
models=[]
for axis in ['u_east','v_south']:
 mx=sum(x[axis] for x,y in train)/len(train);my=sum(y[axis] for x,y in train)/len(train)
 var=sum((x[axis]-mx)**2 for x,y in train);b=sum((x[axis]-mx)*(y[axis]-my) for x,y in train)/max(var,1e-9);models.append((my-b*mx,b))
errors={'persistence':[],'ar1':[]};predictions=[]
for x,y in test:
 for name in errors:
  pred=[x['u_east'],x['v_south']] if name=='persistence' else [c+b*x[k] for (c,b),k in zip(models,['u_east','v_south'])]
  true=[y['u_east'],y['v_south']];ps=math.hypot(*pred);ts=y['speed_ms'];direction=math.degrees(math.atan2(-pred[0],pred[1]))%360
  de=abs((direction-y['from_deg']+180)%360-180) if ps>=1 and ts>=1 else None
  errors[name].append(((ps-ts)**2,abs(ps-ts),sum((p-t)**2 for p,t in zip(pred,true)),de))
  predictions.append([y['utc'],name,*pred,y['u_east'],y['v_south']])
metrics={}
for name,es in errors.items():
 n=len(es);angles=[e[3] for e in es if e[3] is not None]
 metrics[name]={'speed_mae_ms':sum(e[1] for e in es)/n,'speed_rmse_ms':math.sqrt(sum(e[0] for e in es)/n),'vector_rmse_ms':math.sqrt(sum(e[2] for e in es)/n),'direction_mae_deg':sum(angles)/len(angles) if angles else None,'direction_count':len(angles)}
report={'source':d['source'],'fetched_utc':d['fetched_utc'],'station':'TORONTO CITY CENTRE (6158667, Billy Bishop)','observation_count':len(rows),'first_utc':rows[0]['utc'],'last_utc':rows[-1]['utc'],'train_pairs':len(train),'test_pairs':len(test),'test_start_utc':test[0][1]['utc'],'test_end_utc':test[-1][1]['utc'],'horizon_hours':1,'split':'chronological 70/30; no shuffled samples; only consecutive valid hourly pairs','ar1_coefficients':models,'metrics':metrics,'limits':'Station-level one-hour input forecast backtest. Not validation of street-level CFD or altitude winds. Calm wind directions (<1 m/s) excluded from direction MAE.'}
(out/'backtest.json').write_text(json.dumps(report,indent=2))
with (out/'backtest-predictions.csv').open('w') as f:
 w=csv.writer(f);w.writerow(['target_utc','model','predicted_u_east','predicted_v_south','observed_u_east','observed_v_south']);w.writerows(predictions)
print(json.dumps(report,indent=2))
