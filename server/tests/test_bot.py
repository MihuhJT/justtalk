"""Isolated server tests: never use a developer's or production database."""
import asyncio
import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
TEMP = tempfile.TemporaryDirectory()
os.environ['JT_DB_PATH'] = str(Path(TEMP.name) / 'test.db')
spec = importlib.util.spec_from_file_location('jt_test_main', ROOT / 'main.py')
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
from fastapi import HTTPException
from jt_bot import SettingsIn

class BotTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        with h.db() as c:
            for table in ['bot_drafts','bot_effects','bot_messages','bot_requests','bot_reminders','bot_settings','messages','members','chats','users','split_shares','splits','polls','watches','gathers','gather_rsvp','spots','friends']:
                c.execute('DELETE FROM '+table)
            for uid in (1,2,3): c.execute('INSERT INTO users(id,name,username,token,created) VALUES (?,?,?,?,?)',(uid,f'Участник {uid}',f'user{uid}',f'test-{uid}',1))
            c.execute("INSERT INTO chats(id,kind,title) VALUES(1,'group','Тест')")
            c.execute("INSERT INTO chats(id,kind,title) VALUES(2,'group','Чужой чат')")
            for uid in (1,2): c.execute('INSERT INTO members(chat_id,user_id) VALUES(1,?)',(uid,))
            c.execute('INSERT INTO members(chat_id,user_id) VALUES(2,3)')
        self.bot = h.bot

    def request(self, mid=1):
        with h.db() as c:
            c.execute('INSERT OR IGNORE INTO messages(id,chat_id,sender_id,text,created) VALUES(?,1,1,?,1)',(mid,'@бот помоги'))
        return {'message_id':mid,'user_id':1,'chat_id':1,'target_chat_id':1,'style':'friendly'}

    async def test_mention_only(self):
        self.assertFalse(self.bot.eligible('Привет всем','group'))
        self.assertTrue(self.bot.eligible('@БОТ помоги','dm'))
        self.assertFalse(self.bot.eligible('> @бот помоги','group'))
        self.assertFalse(self.bot.eligible('```@бот```','dm'))
        self.assertFalse(self.bot.eligible('@ботик','group'))
        self.assertTrue(self.bot.eligible('Привет','bot'))

    async def test_settings_are_personal_and_target_checked(self):
        self.bot.set_settings(SettingsIn(style='brief',target_chat_id=1),'test-1')
        self.assertEqual(self.bot.get_settings('test-1')['style'],'brief')
        self.assertEqual(self.bot.get_settings('test-2')['style'],'friendly')
        with self.assertRaises(HTTPException): self.bot.set_settings(SettingsIn(target_chat_id=2),'test-1')
        with self.assertRaises(HTTPException): self.bot.set_settings(SettingsIn(style='evil'),'test-1')

    async def test_private_chat_isolated(self):
        a=self.bot.open_chat('test-1');b=self.bot.open_chat('test-2')
        self.assertNotEqual(a['id'],b['id'])
        self.assertEqual(a['kind'],'bot')
        with self.assertRaises(HTTPException): self.bot.state(a['id'],'test-2')

    async def test_context_20_same_chat_not_future_deleted_or_cleared(self):
        with h.db() as c:
            for i in range(1,31): c.execute('INSERT INTO messages(id,chat_id,sender_id,text,created) VALUES(?,1,2,?,1)',(i,f'История {i}'))
            c.execute('INSERT INTO messages(id,chat_id,sender_id,text,created) VALUES(31,2,3,?,1)',('Секрет чужого чата',))
        r=self.request(32)
        data,count=self.bot.context(r)
        self.assertEqual(count,20)
        text=json.dumps(data,ensure_ascii=False)
        self.assertNotIn('Секрет чужого чата',text)
        history=json.loads(data[1]['content'].split('\n',1)[1]);self.assertEqual(history[0]['text'],'История 11')
        with h.db() as c:
            c.execute('UPDATE members SET cleared=28 WHERE chat_id=1 AND user_id=1')
            c.execute('UPDATE messages SET deleted=1 WHERE id=29')
        _,count=self.bot.context(r);self.assertEqual(count,1)

    async def test_real_split_card_attribution_and_idempotency(self):
        r=self.request()
        args={'title':'Заказ','total':1200,'user_ids':[1,2],'amounts':[450,750],'details':''}
        result,_=await self.bot.execute(r,'create_split',args,[])
        same,_=await self.bot.execute(r,'create_split',args,[])
        self.assertEqual(result['message_id'],same['message_id'])
        with h.db() as c:
            self.assertEqual(c.execute('SELECT COUNT(*) FROM splits').fetchone()[0],1)
            m=c.execute('SELECT * FROM messages WHERE id=?',(result['message_id'],)).fetchone()
            out=h.msg_json(m,c,1)
        self.assertEqual(out['sender_id'],-1);self.assertEqual(out['bot']['requested_by'],1)
        self.assertEqual([s['amount'] for s in out['card']['shares']],[450,750])

    async def test_denies_invalid_participants_and_totals(self):
        r=self.request()
        args={'title':'Заказ','total':100,'user_ids':[1,3],'amounts':[],'details':''}
        with self.assertRaises(ValueError): await self.bot.execute(r,'create_split',args,[])
        args.update(user_ids=[1,2],amounts=[1,2])
        with self.assertRaises(HTTPException): await self.bot.execute(r,'create_split',args,[])
        with h.db() as c: self.assertEqual(c.execute('SELECT COUNT(*) FROM splits').fetchone()[0],0)

    async def test_watch_gather_poll_and_private_spot_access(self):
        r=self.request()
        with h.db() as c:
            c.execute("INSERT INTO spots(id,owner_id,title,kind,lat,lng,vis,created) VALUES(1,3,'Скрытый','place',1,1,'me',1)")
            c.execute("INSERT INTO spots(id,owner_id,title,kind,lat,lng,vis,created) VALUES(2,1,'Тестовый','place',1,1,'me',1)")
        spots,_=await self.bot.execute(r,'find_spots',{'query':''},[])
        self.assertEqual([s['id'] for s in spots['spots']],[2])
        watch,_=await self.bot.execute(r,'create_watch',{'title':'Видео','url':'https://youtu.be/abcdef12345'},[])
        self.assertEqual(watch['card_type'],'watch')
        from datetime import datetime,timedelta,timezone
        at=(datetime.now(timezone.utc)+timedelta(hours=2)).isoformat()
        gather,_=await self.bot.execute(r,'create_gather',{'title':'Встреча','at':at,'spot_id':2,'place':'','note':''},[])
        self.assertEqual(gather['card_type'],'gather')
        with self.assertRaises(HTTPException): await self.bot.execute(r,'create_gather',{'title':'Встреча','at':at,'spot_id':1,'place':'','note':''},[])
        poll,_=await self.bot.execute(r,'create_poll',{'question':'Когда?','options':['Сегодня','Завтра'],'multi':False,'anon':False},[])
        self.assertEqual(poll['card_type'],'poll')

    async def test_unknown_tool_and_unselected_destination(self):
        r=self.request();r['target_chat_id']=0
        with self.assertRaises(ValueError): await self.bot.execute(r,'delete_all',{},[])
        with self.assertRaises(ValueError): await self.bot.execute(r,'create_watch',{'title':'x','url':'https://youtu.be/x'},[])

    async def test_reminder_is_durable_and_time_validated(self):
        r=self.request()
        with self.assertRaises(ValueError): await self.bot.execute(r,'create_reminder',{'text':'x','at':'2000-01-01T10:00:00+03:00'},[])
        from datetime import datetime,timedelta,timezone
        args={'text':'Не забыть','at':(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()}
        await self.bot.execute(r,'create_reminder',args,[]);await self.bot.execute(r,'create_reminder',args,[])
        with h.db() as c:self.assertEqual(c.execute('SELECT COUNT(*) FROM bot_reminders').fetchone()[0],1)

    async def test_equal_permissions_and_preserve_paid(self):
        r=self.request();result,_=await self.bot.execute(r,'create_split',{'title':'x','total':100,'user_ids':[1,2],'amounts':[30,70],'details':''},[])
        with h.db() as c:sid=c.execute('SELECT id FROM splits').fetchone()['id']
        with self.assertRaises(HTTPException):await self.bot.equal(sid,'test-2')
        card=await self.bot.equal(sid,'test-1');self.assertEqual([s['amount'] for s in card['shares']],[50,50])
        with h.db() as c:c.execute('UPDATE split_shares SET paid=1 WHERE split_id=? AND user_id=2',(sid,))
        with self.assertRaises(HTTPException):await self.bot.equal(sid,'test-1')

    async def test_draft_only_sends_on_owner_confirmation_and_no_private_quote_leak(self):
        r=self.request()
        result,_=await self.bot.execute(r,'prepare_message',{'user_id':2,'text':'Как дела?'},[])
        with h.db() as c:
            d=c.execute('SELECT * FROM bot_drafts').fetchone();did=d['id']
            self.assertEqual(d['sent_message_id'],0)
            self.assertEqual(c.execute("SELECT COUNT(*) FROM chats WHERE kind='dm'").fetchone()[0],0)
        with self.assertRaises(HTTPException):await self.bot.send_draft(did,'test-2')
        first=await self.bot.send_draft(did,'test-1');again=await self.bot.send_draft(did,'test-1')
        self.assertTrue(first['sent']);self.assertTrue(again['sent'])
        with h.db() as c:
            msgs=c.execute("SELECT m.* FROM messages m JOIN chats ch ON ch.id=m.chat_id WHERE ch.kind='dm'").fetchall()
            self.assertEqual(len(msgs),1)
            self.assertEqual(msgs[0]['reply_to'],0)
            out=h.msg_json(msgs[0],c,2)
            self.assertNotIn('reply',out)
            self.assertEqual(out['text'],'Как дела?')
            self.assertEqual(out['bot']['requested_by'],1)
            self.assertEqual(out['bot']['target_chat_id'],0)

    async def test_relative_time_uses_moscow_and_original_request(self):
        from datetime import datetime,timedelta
        from jt_bot import MOSCOW
        expected=(datetime.now(MOSCOW)+timedelta(days=1)).replace(hour=19,minute=0,second=0,microsecond=0)
        self.assertEqual(self.bot.parse_time('2099-01-01T16:00:00Z','Позови завтра к 19:00 по Москве'),int(expected.timestamp()))
        self.assertEqual(self.bot.parse_time('завтра 19:00'),int(expected.timestamp()))

    async def test_bot_answer_uses_no_tools(self):
        from unittest.mock import patch
        self.request()
        with h.db() as c:c.execute("INSERT INTO bot_requests VALUES(1,1,1,0,'brief','queued',1)")
        observed=[]
        def reply(messages, allow_tools):
            observed.append((allow_tools, messages[0]['content']))
            return {'content':'Короткий ответ.'}
        with patch.object(self.bot,'completion',reply): await self.bot.run(1)
        self.assertEqual(observed[0][0],False)
        self.assertIn('двух коротких предложений',observed[0][1])
        with h.db() as c:
            self.assertEqual(c.execute('SELECT COUNT(*) FROM polls').fetchone()[0],0)

    async def test_contact_declension_and_no_unconfirmed_send(self):
        with h.db() as c:
            c.execute("UPDATE users SET name='Рома' WHERE id=2")
            self.assertEqual([x['id'] for x in self.bot.contacts(c,1,'rome')],[2])
            self.assertEqual(self.bot.contacts(c,1,'несуществующий'),[])
        r=self.request()
        with self.assertRaises(ValueError): await self.bot.execute(r,'prepare_message',{'user_id':3,'text':'Привет'},[])

    async def test_false_action_confirmation_is_rejected(self):
        from unittest.mock import patch
        self.request()
        with h.db() as c:
            c.execute("UPDATE messages SET text='@бот создай опрос' WHERE id=1")
            c.execute("INSERT INTO bot_requests VALUES(1,1,1,0,'friendly','queued',1)")
        with patch.object(self.bot,'completion',lambda *_: {'content':'Я создал опрос!'}): await self.bot.run(1)
        with h.db() as c:
            self.assertEqual(c.execute('SELECT COUNT(*) FROM polls').fetchone()[0],0)
            self.assertFalse(c.execute("SELECT 1 FROM messages WHERE sender_id=-1 AND text='Я создал опрос!'").fetchone())

    async def test_dm_history_survives_cleared_chat(self):
        with h.db() as c:
            c.execute("INSERT INTO chats(id,kind,title,dm_key) VALUES(3,'dm',NULL,'1:2')")
            c.execute("INSERT INTO members(chat_id,user_id) VALUES(3,1),(3,2)")
            c.execute("INSERT INTO messages(chat_id,sender_id,text,created) VALUES(3,1,'Привет',1)")
            c.execute("UPDATE members SET cleared=999 WHERE chat_id=3 AND user_id=1")
            chat = h.chat_json(c,3,1)
        self.assertTrue(chat['has_history'])
        self.assertIsNone(chat['last'])
        with h.db() as c:
            c.execute("UPDATE members SET hidden=1 WHERE chat_id=3 AND user_id=1")
        users = h.users('test-1')
        self.assertTrue(next(u for u in users if u['id']==2)['corresponded'])

    async def test_channel_owner_controls_posts(self):
        from jt_bot import SettingsIn
        channel=await h.create_channel(h.ChannelIn(title='Новости',user_ids=[2]), 'test-1')
        self.assertEqual(channel['kind'],'channel')
        self.assertEqual(channel['owner_id'],1)
        with h.db() as c:
            h.check_channel_post(c,channel['id'],1)
            with self.assertRaises(HTTPException): h.check_channel_post(c,channel['id'],2)
        with self.assertRaises(HTTPException):
            await h.pin_message(channel['id'],h.PinMessageIn(message_id=0),'test-2')

if __name__=='__main__':unittest.main()
