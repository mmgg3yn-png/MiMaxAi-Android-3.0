from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.button import Button

class MiMaxStarter(App):
    def build(self):
        root = BoxLayout(orientation="vertical", padding=24, spacing=16)
        self.message = Label(text="Hello from Python + Kivy", font_size="22sp")
        button = Button(text="Tap here", size_hint_y=None, height="52dp")
        button.bind(on_release=self.on_click)
        root.add_widget(self.message)
        root.add_widget(button)
        return root
    def on_click(self, *_):
        self.message.text = "The app works!"

if __name__ == "__main__":
    MiMaxStarter().run()
