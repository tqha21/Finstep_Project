"use client";
import { useCallback, useRef, useState } from "react";
import { api, errorMessage } from "@/lib/api";
import { useResource } from "@/lib/use-resource";
import { useUser } from "@/components/protected-shell";
import { Busy, ErrorNotice } from "@/components/feedback";
import { dateTime } from "@/lib/format";
type Feedback={id:string;type:"BUG"|"IDEA"|"OTHER";message:string;createdAt:string};
const labels={BUG:"Báo lỗi",IDEA:"Góp ý",OTHER:"Khác"};
export default function FeedbackPage() {
  const user=useUser(),load=useCallback(()=>api<Feedback[]>("/feedback"),[]),resource=useResource("feedback",load);
  const [pending,setPending]=useState(false),[error,setError]=useState(""),[message,setMessage]=useState("");
  const retry=useRef<{fingerprint:string;key:string}|null>(null);
  async function submit(event:React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if(pending) return; setPending(true);setError("");setMessage("");
    const element=event.currentTarget,form=new FormData(element),body={type:String(form.get("type")),message:String(form.get("message")).trim()};
    try {
      if(body.message.length<10) throw new Error("Hãy nhập ít nhất 10 ký tự sau khi bỏ khoảng trắng.");
      const fingerprint=JSON.stringify(body);
      if(!retry.current || retry.current.fingerprint!==fingerprint) retry.current={fingerprint,key:crypto.randomUUID()};
      await api<Feedback>("/feedback","POST",body,retry.current.key);
      retry.current=null; element.reset();setMessage("Đã ghi nhận phản hồi. Bạn có thể xem trong danh sách bên dưới.");resource.reload();
    } catch(cause) {setError(errorMessage(cause));} finally {setPending(false);}
  }
  return <><header className="dashboard-header"><div><span className="eyebrow">Cùng hoàn thiện Finstep</span><h1>Gửi phản hồi</h1><p>Báo lỗi hoặc góp ý về trải nghiệm của bạn.</p></div></header><section className="panel profile-panel" aria-label="Form phản hồi">{message && <p role="status" className="success-message">{message}</p>}{error && <ErrorNotice message={error}/>}<form method="post" onSubmit={submit} className="stack-form"><fieldset disabled={pending}><label>Loại phản hồi<select name="type" aria-label="Loại phản hồi">{Object.entries(labels).map(([value,label])=><option value={value} key={value}>{label}</option>)}</select></label><label>Nội dung phản hồi<textarea aria-label="Nội dung phản hồi" name="message" minLength={10} maxLength={2000} rows={6} required placeholder="Bạn gặp điều gì? Bạn mong muốn Finstep hoạt động ra sao?"/><span className="field-hint">10–2.000 ký tự. Không gửi mật khẩu, mã xác minh hoặc thông tin tài chính nhạy cảm.</span></label><p className="field-hint">Phản hồi được lưu cùng tài khoản, chưa có cam kết thời gian trả lời. Nếu kết nối lỗi, giữ nội dung và thử lại trong form này để tránh gửi trùng.</p></fieldset><button className="button button-primary" disabled={pending}>{pending?"Đang gửi…":"Gửi phản hồi"}</button></form></section><section className="panel profile-panel" aria-labelledby="feedback-history"><div className="panel-heading"><div><h2 id="feedback-history">Phản hồi của bạn</h2><p>20 phản hồi gần nhất. Bản xuất dữ liệu cá nhân gồm toàn bộ phản hồi.</p></div></div>{resource.loading?<Busy/>:resource.error?<><ErrorNotice message={errorMessage(resource.error)}/><button className="button button-secondary" onClick={resource.reload}>Thử lại</button></>:resource.data?.length?<ul className="feedback-list">{resource.data.map(item=><li key={item.id}><div className="actions"><strong>{labels[item.type]}</strong><time dateTime={item.createdAt}>{dateTime(item.createdAt,user.timezone)}</time></div><p>{item.message}</p></li>)}</ul>:<p className="muted">Bạn chưa gửi phản hồi nào.</p>}</section></>;
}
