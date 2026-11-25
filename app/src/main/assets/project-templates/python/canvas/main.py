from kivy.app import App
from kivy.uix.widget import Widget
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.graphics import Color, Ellipse

class DrawingArea(Widget):
    def on_touch_down(self, touch):
        with self.canvas:
            Color(0.2, 0.55, 0.95, 1)
            Ellipse(pos=(touch.x-18,touch.y-18),size=(36,36))
        return True

class CanvasStarter(App):
    def build(self):
        root=BoxLayout(orientation="vertical")
        area=DrawingArea();root.add_widget(area)
        clear=Button(text="Clear drawing",size_hint_y=None,height="52dp")
        clear.bind(on_release=lambda *_:area.canvas.clear());root.add_widget(clear)
        return root

if __name__ == "__main__": CanvasStarter().run()
